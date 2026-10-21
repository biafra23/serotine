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
import eth.serotine.android.widget.RateRefreshJob
import eth.serotine.android.widget.RateWidget
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.engine.AndroidDnsServers
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.history.FileHistoryStarts
import eth.serotine.data.keys.AndroidBiometricKey
import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.PasswordOnlyTier
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.rates.FileLastExchangeRate
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
import eth.serotine.domain.usecase.FollowExchangeRate
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.FollowSentTransactions
import eth.serotine.domain.usecase.FollowUpgradeReports
import eth.serotine.domain.usecase.HistoryNotingAccounts
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ReadExchangeRate
import eth.serotine.domain.usecase.RefreshExchangeRate
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform, and the owner of the process's one Myotis engine
 * (docs/embedding-myotis.md). Built once per process; activities read [graph]. The engine's
 * networking is on while something holds it ([holdEngine]: a visible activity, the daily
 * upgrade check), and while it is, the engine's upgrade reports are followed into
 * notifications ([upgradeNotices]), the Safes this wallet deployed are listed once the light
 * client proves them ([FollowSafeDeployments]), the engine's log index follows the accounts
 * ([FollowAccountLogs]) and, while an activity is visible and an exchange-rate widget is
 * placed, its rate is kept current ([FollowExchangeRate]): the work no screen owns, run here
 * beside the [graph], on the same adapters, and never from the shared UI. The widget's refresh
 * while the app is closed ([RateRefreshJob]) wakes the engine alone, without that work
 * ([refreshRateInBackground]).
 */
class SerotineApplication : Application() {

    // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the background
    // and keeps its state under the app's private files; on an ABI the engine library does
    // not ship (it has arm64-v8a and x86_64), balances say so. The balances last verified are
    // kept with the other files (none of which leave the device), to be shown as such after a
    // restart until a head can be read. The adapters' diagnostics and the engine's own log go to
    // logcat in the debug app only (debuggable below).
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

    /**
     * The display currency, one instance, which Settings writes and the exchange-rate widget
     * reads, so the widget follows a change at once.
     */
    private val displayCurrency by lazy { FileDisplayCurrency(filesDir.resolve("display-currency.json").path) }

    /**
     * The exchange rate last verified, for the widget, beside the other files: a price and its
     * times, public data only. Read without the engine, so drawing a widget never starts it.
     */
    private val lastRate by lazy { FileLastExchangeRate(filesDir.resolve("last-exchange-rate.json").path) }

    /** Reads the widget's rate, and the one of a day before, through the engine, and keeps it in [lastRate]. */
    private val refreshRate by lazy {
        RefreshExchangeRate(
            ObserveDisplayCurrencies(node.priceFeeds, displayCurrency),
            ReadExchangeRate(node.priceFeeds, node.priceHistory),
            lastRate,
            Network.SEPOLIA,
        )
    }

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
            displayCurrency = displayCurrency,
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

    /**
     * Where the widget's refresh is scheduled and cancelled: one at a time, in the order asked,
     * so a cancel asked for before a schedule never lands after it.
     */
    private val jobScheduling = Dispatchers.Default.limitedParallelism(1)

    // The holds on the engine, and what they keep running: main thread only. [holds] counts every
    // hold, [appHolds] those that run the app's background work too (all but the widget's
    // refresh), [visible] the activities started, while which a placed widget's rate is followed.
    private val handler = Handler(Looper.getMainLooper())
    private var holds = 0
    private var appHolds = 0
    private var visible = 0
    private var following: Job? = null
    private var deployments: Job? = null
    private var sentTransactions: Job? = null
    private var accountLogs: Job? = null
    private var rateFollowing: Job? = null
    private val sleep = Runnable { node.pause() }
    private val stopAppWork = Runnable {
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
        // Kept scheduled while a widget is placed, whatever cancelled it (a widget restored by a
        // launcher, say): the job's own period is kept.
        scope.launch(jobScheduling) {
            val app = this@SerotineApplication
            if (RateWidget.placed(app)) RateRefreshJob.schedule(app, now = false)
        }
    }

    /**
     * Keeps the engine's networking on, its upgrade reports followed into notifications, the
     * Safes this wallet deployed listed once proven, how the transactions sent from here end
     * recorded, and the accounts followed by the engine's log index, until the matching
     * [releaseEngine]. Main thread.
     */
    fun holdEngine() {
        wakeEngine()
        if (appHolds++ > 0) return
        handler.removeCallbacks(stopAppWork)
        // Not active: never started, stopped by the last stopAppWork, or ended with an engine that is down.
        if (following?.isActive != true) {
            following = scope.launch { FollowUpgradeReports(node.networkUpgrades, upgradeNotices)() }
        }
        if (deployments?.isActive != true) deployments = scope.launch { followSafeDeployments() }
        if (sentTransactions?.isActive != true) sentTransactions = scope.launch { followSentTransactions() }
        if (accountLogs?.isActive != true) accountLogs = scope.launch { followAccountLogs() }
    }

    /** Turns the engine's networking on for one more hold. Main thread. */
    private fun wakeEngine() {
        if (holds++ > 0) return
        handler.removeCallbacks(sleep)
        node.resume()
    }

    /** Ends a [wakeEngine]: [PAUSE_DELAY_MS] after the last hold of any kind, networking goes off. Main thread. */
    private fun sleepEngine() {
        if (--holds == 0) handler.postDelayed(sleep, PAUSE_DELAY_MS)
    }

    /**
     * Keeps a placed exchange-rate widget's rate current while an activity is visible, its light
     * client running anyway ([FollowExchangeRate]): read when it is more than
     * [RATE_MAX_AGE_MINUTES] old or in another currency than the one chosen now, and drawn.
     * Never for a hold of the background jobs, which may run on a metered network: the
     * widget's own refresh is [RateRefreshJob]'s. Nothing is read without a widget. Main thread.
     */
    private fun followRateForWidgets() {
        if (visible == 0 || rateFollowing?.isActive == true) return
        rateFollowing = scope.launch {
            if (!RateWidget.placed(this@SerotineApplication)) return@launch
            try {
                FollowExchangeRate(
                    displayCurrency,
                    refreshRate,
                    maxAgeSeconds = RATE_MAX_AGE_MINUTES * 60,
                    checkEvery = RATE_CHECK_MINUTES.minutes,
                    readTimeout = RATE_READ_TIMEOUT,
                ).invoke { RateWidget.draw(this@SerotineApplication, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Serotine: following the widget's exchange rate stopped: ${e::class.simpleName}")
            }
        }
    }

    /**
     * Reads the exchange rate once for the widget, with the engine awake for this read alone and
     * none of the app's other background work, draws it, and then calls [done] on the main
     * thread. Without a placed widget nothing wakes: the refresh is cancelled, [done] is called
     * and null returned. Cancelling the job it returns gives the read up; [done] is called then
     * too. Main thread.
     */
    fun refreshRateInBackground(done: () -> Unit): Job? {
        if (!RateWidget.placed(this)) {
            scope.launch(jobScheduling) { RateRefreshJob.cancel(this@SerotineApplication) }
            handler.post(done)
            return null
        }
        wakeEngine()
        return scope.launch {
            withTimeoutOrNull(RATE_READ_TIMEOUT) { refreshRate() }
                ?.let { RateWidget.draw(this@SerotineApplication, it) }
        }.also { job ->
            // On completion, not in a finally: a job cancelled before it started never runs its
            // body, and the hold must end anyway.
            job.invokeOnCompletion {
                handler.post {
                    sleepEngine()
                    done()
                }
            }
        }
    }

    /** Draws every placed widget from the rate kept, without the engine; then [done]. */
    fun drawRateWidgets(done: () -> Unit) {
        scope.launch {
            try {
                RateWidget.draw(this@SerotineApplication, lastRate.load())
            } finally {
                done()
            }
        }
    }

    /** The first widget was placed: schedules its refresh, and follows its rate if the app is open. Main thread. */
    fun rateWidgetPlaced() {
        scope.launch(jobScheduling) { RateRefreshJob.schedule(this@SerotineApplication, now = true) }
        followRateForWidgets()
    }

    /** The last widget was removed: nothing is read for one any more. Main thread. */
    fun rateWidgetsRemoved() {
        rateFollowing?.cancel()
        rateFollowing = null
        scope.launch(jobScheduling) { RateRefreshJob.cancel(this@SerotineApplication) }
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
     * Ends a [holdEngine]. [PAUSE_DELAY_MS] after the last one, neither the reports nor the
     * deployments are followed (the reminder alarm covers the reports until the next hold, and
     * a deployment's record keeps it for the next), whatever else still holds the engine, and
     * once nothing does, networking goes off (a backgrounded wallet keeps no sockets or timers
     * open). The delay keeps a rotation or a quick app switch from pausing at all. Main thread.
     */
    fun releaseEngine() {
        if (--appHolds == 0) handler.postDelayed(stopAppWork, PAUSE_DELAY_MS)
        sleepEngine()
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

    /** Holds the engine, and follows a placed widget's rate, while any activity is visible. */
    private inner class HoldWhileVisible : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visible++
            holdEngine()
            followRateForWidgets()
        }

        override fun onActivityStopped(activity: Activity) {
            if (--visible == 0) {
                rateFollowing?.cancel()
                rateFollowing = null
            }
            releaseEngine()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val PAUSE_DELAY_MS = 30_000L

        /** How old the widget's rate may be while the app is open before it is read again. */
        const val RATE_MAX_AGE_MINUTES = 30L

        /** How often the app, while open, looks whether the widget's rate is due. */
        const val RATE_CHECK_MINUTES = 5

        /** How long one read of the widget's rate may wait for the light client. */
        val RATE_READ_TIMEOUT = 4.minutes

        /** The logcat tag of the debug app's diagnostics. */
        const val LOG_TAG = "Serotine"
    }
}
