package eth.serotine.ios

import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.PasswordOnlyTier
import eth.serotine.data.keys.Secp256k1Recovery
import eth.serotine.data.keys.iosHardwareSecret
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
import eth.serotine.domain.usecase.FollowSafeDeployments
import eth.serotine.domain.usecase.FollowSentTransactions
import eth.serotine.domain.usecase.FollowUpgradeReports
import eth.serotine.domain.usecase.HistoryRecordingSentTransfers
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.UpgradeNotices
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.AppGraph
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The iOS composition root: the process's one wallet, which the Swift app creates once, before
 * its first frame, and hands to [MainViewController]. It builds the [graph] from this platform's
 * adapters around the Myotis engine (docs/embedding-myotis.md), on the main thread as the desktop
 * does before its window: nothing here waits on the network, and only a simulator build may ask
 * the Secure Enclave anything ([iosHardwareSecret]). Beside the graph, on the same adapters, it
 * runs the work no screen owns: the engine's network-upgrade reports followed into
 * notifications, the Safes this wallet deployed listed once the light client proves them, and
 * how the transactions sent from here end recorded. That work runs while the app is in the
 * foreground; in the background the engine's networking is paused (a suspended app keeps no
 * sockets open) and resumed on return, and a reminder due meanwhile comes as the notification
 * [IosUpgradeNotifier] scheduled.
 */
class SerotineWallet {

    /** The app's own Application Support directory: in its sandbox, never shown to the user. */
    private val dataDir: String = run {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: error("no Application Support directory")
        "$base/Serotine"
    }

    // Before the graph: on the simulator, a wallet with no keys yet asks whether the Secure
    // Enclave works (iosHardwareSecret says when). The key store creates the directory and
    // keeps it out of backups (docs/keys-and-signing.md, rule 8).
    private val keys = "$dataDir/keys"
    private val hardware = iosHardwareSecret(keys)

    // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the background;
    // its state can be fetched again, so it stays out of backups. The balances last verified are
    // kept beside it, to be shown as such after a restart. The adapters' diagnostics go to the
    // console in a debug build only.
    @OptIn(ExperimentalNativeApi::class)
    private val node: MyotisNode = run {
        val engineDir = "$dataDir/myotis"
        createDirectoryOutOfBackups(engineDir)
        val debugLog: ((String) -> Unit)? = if (Platform.isDebugBinary) { line -> println(line) } else null
        MyotisNode.start(Network.SEPOLIA, engineDir, lastBalances = "$dataDir/last-balances.json", debugLog = debugLog)
    }

    // One record per process, read by the screens and written by the notices; this version's only.
    private val knownUpgrades = FileKnownUpgradeStore("$dataDir/upgrade.json", APP_VERSION)
    private val notifier = IosUpgradeNotifier(Network.SEPOLIA)
    private val upgradeNotices = UpgradeNotices(knownUpgrades, notifier)

    // One instance each, which the graph and the background work share.
    private val accounts = FileAccountRepository("$dataDir/accounts.json")

    // The transactions signed here, with their signed bytes (public once broadcast), and every one
    // of them kept for good with how it ended (issue #85): each record goes into both.
    private val sentRecord = FileSentTransfers("$dataDir/sent-transfers.json")
    private val transactionHistory = FileTransactionHistory("$dataDir/transaction-history.json")
    private val sentTransfers = HistoryRecordingSentTransfers(sentRecord, transactionHistory)

    internal val graph: AppGraph = run {
        // This device's Secure Enclave; the password alone on a simulator without one, its Argon2id
        // tier sized by the memory the simulator reports (the Mac's). No biometric path yet. One
        // instance is both the key store and the signer, so that their calls take turns.
        val keyStore = EnvelopeKeyStore(
            keys,
            hardware,
            passwordOnlyTier = PasswordOnlyTier.forDevice(
                NSProcessInfo.processInfo.physicalMemory.toLong(),
                lowRamDevice = false,
            ),
        )
        AppGraph(
            accounts = accounts,
            keys = keyStore,
            nativeBalances = node.nativeBalances,
            tokenCatalog = BuiltInTokenCatalog(Network.SEPOLIA),
            tokenBalances = node.tokenBalances,
            tokenMetadata = node.tokenMetadata,
            userTokens = FileUserTokens("$dataDir/tokens.json"),
            priceFeeds = node.priceFeeds,
            displayCurrency = FileDisplayCurrency("$dataDir/display-currency.json"),
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
            // The Safe transactions waiting for their owners, with the signatures collected (public).
            safeTxQueue = FileSafeTxQueue("$dataDir/safe-tx-queue.json"),
            signingRequests = JsonSigningRequestCodec(Network.SEPOLIA),
            signatureRecovery = Secp256k1Recovery(),
            safeApprovals = node.safeApprovals,
            // Safe 1.4.1's singleton, factory and proxy code, pinned in code: what a new Safe is created from.
            safeReleases = PinnedSafeReleases(),
            // Uniswap v3 on Sepolia, its pinned contracts proven by their code before any quote.
            uniswap = node.uniswap,
            network = Network.SEPOLIA,
        )
    }

    /**
     * The background work's scope. A failure in it is reported and ends that one piece of work,
     * never the wallet: the next return to the foreground runs it again.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            println("Serotine: the app's background work stopped: $e")
        },
    )

    /** The work running while the app is in the foreground; main thread only. */
    private var foregroundWork: List<Job> = emptyList()

    init {
        // At launch the app is in the foreground: the work starts with the wallet.
        startForegroundWork()
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            node.pause()
            foregroundWork.forEach { it.cancel() }
            foregroundWork = emptyList()
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) {
            node.resume()
            startForegroundWork()
        }
        notifier.askPermission()
    }

    private fun startForegroundWork() {
        foregroundWork = listOf(
            scope.launch {
                reportingFailure("upgrade notices") { FollowUpgradeReports(node.networkUpgrades, upgradeNotices)() }
            },
            scope.launch {
                // The Safes this wallet deployed are listed whatever screen is open, and again after a
                // restart (docs/wallet-ux.md, screen 15).
                val kinds = node.addressKinds
                reportingFailure("following Safe deployments") {
                    FollowSafeDeployments(
                        sentTransfers,
                        node.receipts,
                        accounts,
                        ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
                    )()
                }
            },
            scope.launch {
                // How each transaction sent from here ends is recorded whatever screen is open (issue #85).
                reportingFailure("following sent transactions") {
                    FollowSentTransactions(transactionHistory, sentRecord, node.receipts, node.transactionInputs)()
                }
            },
        )
    }

    /** Runs [work]; a failure ends it, said on the console with its kind only, never the wallet. */
    private suspend fun reportingFailure(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Serotine: $what stopped: ${e::class.simpleName}")
        }
    }
}

/**
 * Creates [path] and keeps it out of iCloud and computer backups: what can be fetched again
 * does not belong in a backup. Best effort; a failure is said and the engine still starts.
 */
private fun createDirectoryOutOfBackups(path: String) {
    val created = NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val excluded = created && NSURL.fileURLWithPath(path, isDirectory = true)
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    if (!excluded) println("Serotine: the engine's directory is not kept out of backups")
}
