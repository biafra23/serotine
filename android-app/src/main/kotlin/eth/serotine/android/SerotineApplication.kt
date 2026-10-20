package eth.serotine.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import eth.serotine.android.keys.ForegroundActivity
import eth.serotine.android.keys.PlatformBiometricPrompter
import eth.serotine.android.upgrade.AndroidUpgradeNotifier
import eth.serotine.android.upgrade.UpgradeCheckJob
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.engine.AndroidDnsServers
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.history.FileHistoryStarts
import eth.serotine.data.keys.AndroidBiometricKey
import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.PasswordOnlyTier
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.safe.FileSafeTxQueue
import eth.serotine.data.safe.JsonSigningRequestCodec
import eth.serotine.data.safe.PinnedSafeReleases
import eth.serotine.data.settings.FileDisplayCurrency
import eth.serotine.data.tokens.BuiltInTokenCatalog
import eth.serotine.data.tokens.FileUserTokens
import eth.serotine.data.transfers.FileSentTransfers
import eth.serotine.data.transfers.FileTransactionHistory
import eth.serotine.data.upgrade.FileKnownUpgradeStore
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.FollowAccountLogs
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.FollowSentTransactions
import eth.serotine.domain.usecase.FollowUpgradeReports
import eth.serotine.domain.usecase.HistoryNotingAccounts
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.UpgradeNotices
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.AppGraph
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform, and the owner of the process's one Myotis engine
 * (docs/embedding-myotis.md). Built once per process; activities read [graph]. The engine's
 * networking is on while something holds it ([holdEngine]: a visible activity, the daily
 * upgrade check), and while it is, the engine's upgrade reports are followed into
 * notifications ([upgradeNotices]), the Safes this wallet deployed are listed once the light
 * client proves them ([FollowSafeDeployments]) and the engine's log index follows the accounts
 * ([FollowAccountLogs]): the work no screen owns, run here beside the
 * [graph], on the same adapters, and never from the shared UI.
 */
class SerotineApplication : Application() {

    // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the background
    // and keeps its state under the app's private files; on an ABI the engine library does
    // not ship (it has arm64-v8a and x86_64), balances say so. The balances last verified are
    // kept with the other files (none of which leave the device), to be shown as such after a
    // restart until a head can be read. The adapters' diagnostics go to logcat in the debug app
    // only (debuggable below).
    private val node: MyotisNode by lazy {
        MyotisNode.start(
            Network.SEPOLIA,
            filesDir.resolve("myotis"),
            AndroidDnsServers(this),
            lastBalances = filesDir.resolve("last-balances.json").path,
            debugLog = if (debuggable) { line -> Log.d(LOG_TAG, line) } else null,
        )
    }

    /** eth.serotine.debug is debuggable, the release app is not: what the debug app's extras hang on. */
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    /**
     * The upgrade record, for this app version only: one instance, which the screens read and
     * the notices write. Peer reports, no keys; kept out of backups with the rest of the files.
     */
    private val knownUpgrades by lazy { FileKnownUpgradeStore(filesDir.resolve("upgrade.json").path, APP_VERSION) }

    /** What the user hears about an upgrade outside the app. Needs no engine, so a reminder never starts one. */
    private val upgradeNotices by lazy {
        UpgradeNotices(knownUpgrades, AndroidUpgradeNotifier(this, Network.SEPOLIA))
    }

    /**
     * Where each account's history starts (issue #85), beside the account list: addresses with a
     * block or the time each was added, public data only.
     */
    private val historyStarts by lazy { FileHistoryStarts(filesDir.resolve("history-starts.json").path) }

    /**
     * The account list in the app's private files, which the manifest keeps out of backups and
     * device transfers; addresses and labels only, no keys. Each account added is noted in
     * [historyStarts] as it is. One instance, which the graph and the followers share.
     */
    private val accounts by lazy {
        HistoryNotingAccounts(FileAccountRepository(filesDir.resolve("accounts.json").path), historyStarts)
    }

    /**
     * The transactions signed here, with their signed bytes (public once broadcast, no key in
     * them), beside the account list. One instance, which the graph and the deployments' follower
     * share.
     */
    private val sentRecord by lazy { FileSentTransfers(filesDir.resolve("sent-transfers.json").path) }

    /**
     * Every transaction sent from here, kept for good with how each ended (issue #85): what an
     * account's Activity lists. Public data only, as [sentRecord]'s, beside it.
     */
    private val transactionHistory by lazy {
        FileTransactionHistory(filesDir.resolve("transaction-history.json").path)
    }

    /** [sentRecord], each of whose records also goes into [transactionHistory]: what all that sends is handed. */
    private val sentTransfers by lazy { HistoryRecordingSentTransfers(sentRecord, transactionHistory) }

    val graph: AppGraph by lazy {
        // Imported seeds in envelopes under the app's private files, the hardware factor a
        // Keystore HMAC key (docs/keys-and-signing.md); kept out of backups and transfers. The
        // biometric path's key prompts on the activity in front. One instance is both the key
        // store and the signer, so that their calls take turns. Where the password must stand
        // alone (a key store outside secure hardware), its Argon2id tier is sized by this
        // device's memory (the owner's ruling of 2026-10-18).
        val keyStore = EnvelopeKeyStore(
            filesDir.resolve("keys").path,
            AndroidKeystoreSecret(),
            biometricKey = AndroidBiometricKey(this, PlatformBiometricPrompter { foreground.current }),
            passwordOnlyTier = passwordOnlyTier(),
        )
        AppGraph(
            accounts = accounts,
            keys = keyStore,
            nativeBalances = node.nativeBalances,
            tokenCatalog = BuiltInTokenCatalog(Network.SEPOLIA),
            tokenBalances = node.tokenBalances,
            tokenMetadata = node.tokenMetadata,
            userTokens = FileUserTokens(filesDir.resolve("tokens.json").path),
            priceFeeds = node.priceFeeds,
            displayCurrency = FileDisplayCurrency(filesDir.resolve("display-currency.json").path),
            addressKinds = node.addressKinds,
            lightClient = node.lightClient,
            networkUpgrades = node.networkUpgrades,
            knownUpgrades = knownUpgrades,
            transactionInputs = node.transactionInputs,
            broadcaster = node.broadcaster,
            receipts = node.receipts,
            names = node.names,
            signer = keyStore,
            sentTransfers = sentTransfers,
            transactionHistory = transactionHistory,
            historyStarts = historyStarts,
            logIndex = node.logIndex,
            // The Safe transactions waiting for their owners, with the signatures collected (public
            // data, no key in them), beside the account list in the app's private files.
            safeTxQueue = FileSafeTxQueue(filesDir.resolve("safe-tx-queue.json").path),
            signingRequests = JsonSigningRequestCodec(Network.SEPOLIA),
            signatureRecovery = Secp256k1Recovery(),
            safeApprovals = node.safeApprovals,
            // Safe 1.4.1's singleton, factory and proxy code, pinned in code: what a new Safe is created from.
            safeReleases = PinnedSafeReleases(),
            // Uniswap v3 on Sepolia, its pinned contracts proven by their code before any quote.
            uniswap = node.uniswap,
            network = Network.SEPOLIA,
            // The debug app only: Settings may lift FLAG_SECURE for a screen recording, until the
            // process ends. The release app has no switch (docs/keys-and-signing.md, rule 8).
            screenCaptureDebugSwitch = if (debuggable) ScreenCaptureDebugSwitch() else null,
        )
    }

    /**
     * The process's own work: following upgrade reports, showing reminders and listing the
     * Safes deployed. A failure in it is reported and ends that one piece of work, never the
     * wallet: the next hold, alarm or start runs it again.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            System.err.println("Serotine: the app's background work stopped: $e")
        },
    )

    // The holds on the engine, and what they keep running: main thread only.
    private val handler = Handler(Looper.getMainLooper())
    private var holds = 0
    private var following: Job? = null
    private var deployments: Job? = null
    private var sentTransactions: Job? = null
    private var accountLogs: Job? = null
    private val sleep = Runnable {
        node.pause()
        following?.cancel()
        following = null
        deployments?.cancel()
        deployments = null
        sentTransactions?.cancel()
        sentTransactions = null
        accountLogs?.cancel()
        accountLogs = null
    }

    /** The activity in front, for the biometric prompt. */
    private val foreground = ForegroundActivity()

    /**
     * This device's password-only Argon2id tier, from its total memory and whether the system
     * calls it a low-RAM device ([PasswordOnlyTier.forDevice]); the full tier when the system
     * does not say.
     */
    private fun passwordOnlyTier(): PasswordOnlyTier {
        val manager = getSystemService(ActivityManager::class.java) ?: return PasswordOnlyTier.FULL
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return PasswordOnlyTier.forDevice(memory.totalMem, manager.isLowRamDevice)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
        registerActivityLifecycleCallbacks(HoldWhileVisible())
        scope.launch { UpgradeCheckJob.schedule(this@SerotineApplication) }
    }

    /**
     * Keeps the engine's networking on, its upgrade reports followed into notifications, the
     * Safes this wallet deployed listed once proven, how the transactions sent from here end
     * recorded, and the accounts followed by the engine's log index, until the matching
     * [releaseEngine]. Main thread.
     */
    fun holdEngine() {
        if (holds++ > 0) return
        handler.removeCallbacks(sleep)
        node.resume()
        // Not active: never started, stopped by the last sleep, or ended with an engine that is down.
        if (following?.isActive != true) {
            following = scope.launch { FollowUpgradeReports(node.networkUpgrades, upgradeNotices)() }
        }
        if (deployments?.isActive != true) deployments = scope.launch { followSafeDeployments() }
        if (sentTransactions?.isActive != true) sentTransactions = scope.launch { followSentTransactions() }
        if (accountLogs?.isActive != true) accountLogs = scope.launch { followAccountLogs() }
    }

    /**
     * Keeps the engine's log index following every account from where its history starts (issue
     * #85); runs until cancelled. A failure ends it, never the wallet: the next hold follows them
     * again.
     */
    private suspend fun followAccountLogs() {
        try {
            FollowAccountLogs(
                accounts,
                historyStarts,
                transactionHistory,
                node.lightClient,
                node.logIndex,
                Network.SEPOLIA,
            )()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following the accounts' logs stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Records how each transaction sent from here ends, whatever screen is open (issue #85); runs
     * until cancelled. A failure ends it, never the wallet: the next hold follows them again.
     */
    private suspend fun followSentTransactions() {
        try {
            FollowSentTransactions(transactionHistory, sentRecord, node.receipts, node.transactionInputs)()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following sent transactions stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Lists every Safe this wallet deployed once the light client proves it, whatever screen is
     * open (docs/wallet-ux.md, screen 15); runs until cancelled. A failure ends it, never the
     * wallet: the next hold follows the deployments again.
     */
    private suspend fun followSafeDeployments() {
        val kinds = node.addressKinds
        try {
            FollowSafeDeployments(
                sentTransfers,
                node.receipts,
                accounts,
                ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
            )()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Serotine: following Safe deployments stopped: ${e::class.simpleName}")
        }
    }

    /**
     * Ends a [holdEngine]. [PAUSE_DELAY_MS] after the last one, networking goes off (a
     * backgrounded wallet keeps no sockets or timers open) and neither the reports nor the
     * deployments are followed: the reminder alarm covers the reports until the next hold, and
     * a deployment's record keeps it for the next. The delay keeps a
     * rotation or a quick app switch from pausing at all. Main thread.
     */
    fun releaseEngine() {
        if (--holds == 0) handler.postDelayed(sleep, PAUSE_DELAY_MS)
    }

    /**
     * Calls [then], on the main thread, with whether a background look for upgrades can learn
     * anything: not once all is said about the known one ([UpgradeNotices.allSaid]). Never
     * starts the engine.
     */
    fun whenUpgradeCheckWanted(then: (Boolean) -> Unit) {
        scope.launch {
            val wanted = !upgradeNotices.allSaid()
            handler.post { then(wanted) }
        }
    }

    /** Shows the upgrade reminder due now, if any, and arms the next; then [done]. Never starts the engine. */
    fun remindOfUpgrades(done: () -> Unit) {
        scope.launch {
            try {
                upgradeNotices.remind()
            } finally {
                done()
            }
        }
    }

    /** Holds the engine while any activity is visible. */
    private inner class HoldWhileVisible : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = holdEngine()
        override fun onActivityStopped(activity: Activity) = releaseEngine()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val PAUSE_DELAY_MS = 30_000L

        /** The logcat tag of the debug app's diagnostics. */
        const val LOG_TAG = "Serotine"
    }
}
