package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.logs.LogSink
import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.tokens.BuiltInTokens
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.CowChain
import eth.serotine.domain.repository.UniswapV3Chain
import io.myotis.engine.ios.BeaconState
import io.myotis.engine.ios.ChainHandle
import io.myotis.engine.ios.EngineConfig
import io.myotis.engine.ios.EnsRoot
import io.myotis.engine.ios.LifecycleState
import io.myotis.engine.ios.Myotis
import io.myotis.engine.ios.MyotisEngine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The embedded Myotis engine of this process on iOS (docs/embedding-myotis.md): one engine
 * hosting one chain, and the verified reads the adapters make through it. The twin of the JVM
 * targets' `MyotisNode` (jvmSharedMain), over Myotis's iOS module (`io.myotis.engine.ios`,
 * which carries `io.myotis.api`'s names and meanings over the engine's C ABI): the same
 * adapters, wired to the same [TransactingChain] seam, and the same lifecycle. The iOS host's
 * composition root creates it with [start], hands its adapters to the AppGraph and owns it
 * for the life of the process: [pause] and [resume] it around the app being in the
 * background, [shutdown] on exit, as far as iOS lets an app's exit run code at all. Nothing
 * below the iOS module's typed API is used.
 */
@OptIn(ExperimentalAtomicApi::class)
class MyotisNode private constructor(
    network: Network,
    lastBalancesPath: String?,
    private val debugLog: ((String) -> Unit)?,
    apiLog: LogSink?,
) {

    private sealed interface State {
        data object Booting : State
        class Running(
            val engine: MyotisEngine,
            val handle: ChainHandle,
            /** The network's beacon genesis (unix seconds) and slot length, from the engine. */
            val genesisTime: Long,
            val secondsPerSlot: Int,
        ) : State {
            fun stop() = engine.shutdownAll()
        }
        data object Down : State
    }

    private val state = AtomicReference<State>(State.Booting)

    /** Every blocking engine call off the caller's thread; the boot, pauses and resumes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var bootJob: Job? = null

    /** The debug build's drain of the engine's own log, while the engine runs. */
    @Volatile
    private var logPump: Job? = null

    /**
     * Whether the host last asked for networking off. A pause can come before the boot has
     * finished (the app backgrounded right after launch); the boot applies it then.
     */
    @Volatile
    private var pauseWanted = false

    /** Runs pause and resume one at a time, in the order asked: each blocks while it lasts. */
    private val control = Dispatchers.IO.limitedParallelism(1)

    /** The engine's handle, unwrapped: what [activity] reads (in memory, so neither logged nor shared). */
    private val handleChain = HandleChain()

    /** The engine's handle, each request written to [apiLog] when there is one, its status reads shared. */
    private val chain: EngineChain = handleChain.let { handle ->
        SharedSnapshots(apiLog?.let { LoggedChain(handle, it) } ?: handle, SNAPSHOT_REUSE)
    }

    /** Which accounts the verified blocks touched, so a proof stands until one did (Myotis #691). */
    private val activity = AddressActivity(handleChain)

    /** The balances last verified, kept for after a restart; none kept without a path. */
    private val lastBalances = lastBalancesPath?.let { LastBalancesFile(it, Dispatchers.IO) }

    /** Native-currency balances, verified by this node's light client. */
    val nativeBalances: NativeBalances = VerifiedNativeBalances(
        network,
        chain,
        Dispatchers.IO,
        POLL_INTERVAL,
        lastBalances,
        activity,
    )

    /** What is at an address (an account, a delegated one, a Safe, a contract), verified here. */
    val addressKinds: AddressKinds =
        VerifiedAddressKinds(
            network,
            chain,
            Dispatchers.IO,
            POLL_INTERVAL,
            SafeDeployments.of(network),
            debugLog = debugLog,
        )

    /** What a token contract says about itself, as verified calls. */
    val tokenMetadata: TokenMetadata = VerifiedTokenMetadata(network, chain, Dispatchers.IO, POLL_INTERVAL)

    /** Prices, each a verified call of one of the network's pinned Chainlink feeds. */
    val priceFeeds: PriceFeeds = VerifiedPriceFeeds(
        network,
        chain,
        Dispatchers.IO,
        POLL_INTERVAL,
        ChainlinkFeeds.of(network),
        activity,
    )

    /** What the same feeds answered some time ago, from their round histories, as verified calls. */
    val priceHistory: PriceHistory = VerifiedPriceHistory(network, chain, Dispatchers.IO, ChainlinkFeeds.of(network))

    /** ERC-20 balances, each a verified call of the token's `balanceOf`. */
    val tokenBalances: TokenBalances = VerifiedTokenBalances(
        network,
        chain,
        Dispatchers.IO,
        POLL_INTERVAL,
        lastBalances,
        activity,
        gated = BuiltInTokens.of(network).mapTo(HashSet()) { it.address },
    )

    /**
     * This node's light client, for the user to watch. It gives no finalized block's own time here:
     * the iOS module reads no block by number yet, so no CoW order is offered on iOS.
     */
    val lightClient: LightClient =
        object : LightClient by EngineLightClient(network, chain, Dispatchers.IO, PROGRESS_POLL_INTERVAL) {
            override val datesFinalizedBlocks: Boolean = false
        }

    /** Network upgrades this build does not support, as peers report them to this node. */
    val networkUpgrades: NetworkUpgrades = EngineNetworkUpgrades(network, chain, Dispatchers.IO, POLL_INTERVAL)

    /** A transaction's nonce, fees, gas and verified simulation, from this node's verified reads. */
    val transactionInputs: TransactionInputs = EngineTransactionInputs(network, chain, Dispatchers.IO)

    /** Gossips a signed transaction to this node's peers (the engine never signs). */
    val broadcaster: Broadcaster = EngineBroadcaster(network, chain, Dispatchers.IO)

    /** A Safe's owners' approvals on chain (`approvedHashes`), each a verified call. */
    val safeApprovals: SafeApprovals = VerifiedSafeApprovals(network, chain, Dispatchers.IO)

    /** Uniswap v3 on chain: the pinned deployment once its code is proven, and verified calls. */
    val uniswap: UniswapV3Chain = VerifiedUniswapV3(chain, Dispatchers.IO, network)

    /**
     * CoW Protocol's settlement contracts on this node's network, proven by their code
     * (docs/swaps.md, *CoW Protocol*).
     */
    val cow: CowChain = VerifiedCow(chain, Dispatchers.IO, network)

    /** A sent transaction's verified receipt and its block's finality. */
    val receipts: ReceiptTracker = EngineReceipts(network, chain, Dispatchers.IO, POLL_INTERVAL)

    /** ENS names, resolved against beacon-finalized state. */
    val names: NameResolver = EngineNames(network, chain, Dispatchers.IO)

    /** The engine's verified log index, which follows the accounts' histories. */
    val logIndex: LogIndex = EngineLogIndex(network, chain, Dispatchers.IO, POLL_INTERVAL)

    /**
     * Turns the engine's networking off, keeping its state, for while the app is in the
     * background. Returns at once with the job the pause runs in, off the caller's thread, so
     * the host can keep the app awake until it has finished. Asked for while the engine is still
     * starting, it is applied once the engine runs, and the job ends only then, so the host
     * keeps the app awake through the rest of the boot and the pause.
     */
    fun pause(): Job {
        pauseWanted = true
        val boot = bootJob
        if (state.load() !is State.Booting || boot == null) return followPauseWanted()
        // The boot follows pauseWanted itself once the engine runs; this waits for that switch.
        return scope.launch {
            boot.join()
            followPauseWanted().join()
        }
    }

    /**
     * Turns networking back on after [pause]; a warm start. Returns at once. A resume the
     * engine refuses (it stays paused) is said on the console; the next resume tries again.
     */
    fun resume(): Job {
        pauseWanted = false
        return followPauseWanted()
    }

    /**
     * Whether the engine's networking is off, as the last switch left it: touched only on the
     * control dispatcher.
     */
    private var networkingPaused = false

    /**
     * Brings the engine's networking, on the control dispatcher, to what the host last asked
     * for ([pauseWanted], read there), so the last pause or resume asked for is the state the
     * engine ends in. A switch to the state it is already in is not asked of the engine, which
     * refuses it (a resume of a running engine, at the first foregrounding).
     */
    private fun followPauseWanted(): Job =
        scope.launch(control) {
            try {
                val handle = (state.load() as? State.Running)?.handle ?: return@launch
                val wanted = pauseWanted
                if (networkingPaused == wanted) return@launch
                if (if (wanted) handle.pause() else handle.resume()) {
                    networkingPaused = wanted
                } else {
                    log("Serotine: the engine did not ${if (wanted) "pause" else "resume"} its networking")
                }
            } catch (e: Throwable) {
                // The engine keeps its last networking state.
                log("Serotine: the engine's networking could not be switched: $e")
            }
        }

    /**
     * Stops the chain and the engine; idempotent. A boot still in progress stops its own
     * engine when it finishes; this waits for that, up to [SHUTDOWN_WAIT], so a process that
     * exits right after does not cut it off mid-start. Blocks: never call it on the main
     * thread, where iOS's watchdog ends an app that blocks; the host calls it off the main
     * thread when the app is terminated while running, and waits for it only briefly. An app
     * killed while suspended runs no code at all, so on iOS this is best effort: the engine
     * has to come back from an abrupt end anyway.
     */
    fun shutdown() {
        val running = state.exchange(State.Down) as? State.Running
        logPump?.cancel()
        running?.stop()
        // In a debug build, what the engine said while stopping, which the pump no longer drains.
        if (running != null) debugLog?.let { EngineLogPump(Myotis::drainLogs, it).drainNow() }
        bootJob?.let { job -> runBlocking { withTimeoutOrNull(SHUTDOWN_WAIT) { job.join() } } }
        scope.cancel()
    }

    private fun boot(network: Network, dataDir: String) {
        if (state.load() != State.Booting) return // shut down before the boot got here
        val engine = Myotis.newEngine()
        val name = network.engineName()
        createDirectory(dataDir)
        // The iOS engine keeps its peer caches, sync snapshot and log index under its data
        // directory, and asks the host for no ports. It has no CCIP-Read gateway either: an
        // off-chain ENS answer (EIP-3668) comes from an HTTP gateway the resolver names, an
        // off-chain source no ruling allows; the engine refuses such a name, and says so.
        // The JVM node's snap-peer target and strict freshness flag have no counterpart here:
        // the Rust engine behind both hosts takes only the name and the data directory
        // (RustMyotisEngine.create passes those two to nativeCreate), so both run the same.
        val handle = try {
            engine.create(EngineConfig(name, dataDir))
        } catch (e: Throwable) {
            try {
                engine.shutdownAll()
            } catch (t: Throwable) {
                e.addSuppressed(t)
            }
            throw e
        }
        // From here until the running state is published, this boot owns the engine: whatever
        // fails releases it, since shutdown() finds nothing to stop yet.
        var owned = true
        try {
            val info = engine.availableNetworks().firstOrNull { it.name == handle.networkName }
            val running = State.Running(engine, handle, info?.clGenesisTime ?: 0, info?.secondsPerSlot ?: 0)
            val started = handle.start()
            // The engine says why only in its own log, at its end, drained before anything stops it.
            val refusal = if (started) null else EngineLogPump.lastLines(Myotis::drainLogs, ENGINE_LOG_LINES)
            val kept = started && state.compareAndSet(State.Booting, running)
            if (!started) state.compareAndSet(State.Booting, State.Down)
            owned = false
            if (!kept) running.stop()
            // A pause asked for during the boot, decided on the control dispatcher: every pause
            // and resume the host asks for from here on queues there too, so the last one asked
            // for is the state the engine ends in.
            if (kept) followPauseWanted()
            // The engine's own log, in a debug build: the one account of why a sync stalls.
            if (kept && debugLog != null) {
                logPump = scope.launch { EngineLogPump(Myotis::drainLogs, debugLog).run(ENGINE_LOG_INTERVAL) }
            }
            refusal?.let { report("the engine refused to start $name:\n$it") }
        } catch (e: Throwable) {
            if (owned) {
                try {
                    engine.shutdownAll()
                } catch (t: Throwable) {
                    e.addSuppressed(t)
                }
            }
            throw e
        }
    }

    private inner class HandleChain : EngineChain, ActivityChain {

        // Each call a status read; SharedSnapshots shares one among the pollers.
        override fun snapshot(): EngineSnapshot = read()

        private fun read(): EngineSnapshot {
            val running = when (val current = state.load()) {
                State.Booting -> return EngineSnapshot.Booting
                State.Down -> return EngineSnapshot.Down
                is State.Running -> current
            }
            val status = try {
                running.handle.status()
            } catch (e: Throwable) {
                // A status out of shape (the iOS module fails closed on drift): no report yet.
                return EngineSnapshot.Booting
            }
            // The head block's time from its slot, the consensus rule for an execution
            // payload's timestamp (genesis + slot × seconds per slot): verified data only. The
            // optimistic slot is the head block's own (Myotis #658): the iOS module decodes it
            // from the same status JSON key ("optimisticSlot") RustChainHandle.beaconStatus()
            // reads on the JVM, so both nodes date the head alike.
            val slot = status.optimisticSlot
            val headTimestamp = if (slot > 0 && running.genesisTime > 0 && running.secondsPerSlot > 0) {
                running.genesisTime + slot * running.secondsPerSlot
            } else {
                null
            }
            return EngineSnapshot.Running(
                phase = when (status.beaconState) {
                    BeaconState.SYNCING -> EngineSnapshot.Phase.SYNCING
                    BeaconState.CATCHING_UP -> EngineSnapshot.Phase.CATCHING_UP
                    BeaconState.SYNCED -> EngineSnapshot.Phase.SYNCED
                    BeaconState.STALE_ANCHOR -> EngineSnapshot.Phase.STALE_ANCHOR
                    BeaconState.STARTING -> EngineSnapshot.Phase.STARTING
                },
                paused = status.lifecycle == LifecycleState.PAUSED,
                syncStartPeriod = status.syncStartPeriod,
                syncCurrentPeriod = status.syncCurrentPeriod,
                syncTargetPeriod = status.syncTargetPeriod,
                huntingLightClientServers = status.lcHunting,
                proofPeers = status.snapServingPeers,
                headBlock = status.optimisticBlockNumber,
                headTimestamp = headTimestamp,
                headAgeMs = status.verifiedHeadAgeMs,
                wsBoundPeriods = status.wsBoundPeriods,
                upgrade = status.upgradeAdvisory?.toNetworkUpgrade(),
                finalizedBlock = status.finalizedBlockNumber,
                // No finalized timestamp on iOS: Myotis 0.1.18's iOS module reads no block by
                // number (its block reads are internal), and the slot's time is no stand-in for the
                // header's (docs/swaps.md, *Proving an order's end*). A CoW order is then not
                // offered here, as it waits for a finalized block's own time.
                finalizedHeader = null,
            )
        }

        private fun handle(): ChainHandle? = (state.load() as? State.Running)?.handle

        // requestAccount: it returns the verdict and the block the proof anchors to, so a
        // reading is labelled with where it was proven.
        override fun proveBalance(address: Address): ProvenBalance? {
            val result = handle()?.requestAccount(address.hex) ?: return null
            if (result.failReason != null || result.verifyMethod == null) return null
            // A proven absence is a proven zero.
            return ProvenBalance(
                if (result.exists) result.balanceWei ?: return null else "0",
                result.blockNumber,
                result.blockHashHex?.lowercase(),
            )
        }

        // The code hash comes with the account proof, labelled with its block; the code itself
        // from the verified reads; provenCode decides what of it is proven.
        override fun proveCode(address: Address): ProvenCode? {
            val handle = handle() ?: return null
            val account = handle.requestAccount(address.hex)
            if (account.failReason != null || account.verifyMethod == null) return null
            val anchor = accountProofAnchor(account.verifyMethod, account.matchedBeaconSlot, account.blockNumber)
            return provenCode(account.exists, account.codeHashHex, account.blockNumber, anchor) {
                handle.reads().getCode(address.bytes(), "latest")
            }
        }

        override fun call(to: Address, data: ByteArray): CallOutcome? {
            val handle = handle() ?: return null
            return callOutcome(handle.reads().callDetailed(null, to.bytes(), data, null, "latest", null))
        }

        // Accepted only with a verified storage proof tied to the beacon chain and a verdict, as
        // balances are; a proven empty slot is a proven zero. Labelled as storageProofLabel says.
        override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
            val result = handle()?.getStorageProof(address.hex, slot, holder?.hex) ?: return null
            if (result.failReason != null || !result.storageProofValid || !result.beaconChainVerified) return null
            val label = storageProofLabel(
                verifyMethod = result.verifyMethod,
                matchedBeaconSlot = result.matchedBeaconSlot,
                finalizedSlot = result.finalizedSlot,
                optimisticSlot = result.optimisticSlot,
                peerBlockNumber = result.peerBlockNumber,
                finalizedBlockNumber = result.finalizedBlockNumber,
            ) ?: return null
            val value = if (result.exists) result.valueHex?.removePrefix("0x")?.lowercase() ?: return null else ""
            return value.padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
                ?.let { ProvenSlot(it, label.blockNumber, label.anchor) }
        }

        override fun storageAt(address: Address, slot: ByteArray): String? {
            val word = handle()?.reads()?.getStorageAt(address.bytes(), slot, "latest") ?: return null
            return word.toHex().padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
        }

        // "pending": the verified count plus the sends this node made that are not mined yet,
        // so a second transfer right after a first does not reuse its nonce.
        override fun pendingNonce(address: Address): Long? =
            handle()?.reads()?.getTransactionCount(address.bytes(), "pending")

        override fun minedNonce(address: Address): Long? =
            handle()?.reads()?.getTransactionCount(address.bytes(), "latest")

        override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? =
            handle()?.reads()?.feeHistory(blocks.toLong(), "latest", percentiles)

        override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer {
            val handle = handle() ?: return EstimateAnswer.Unavailable("no engine running")
            return estimateAnswer(handle.reads().estimateGasDetailed(from.bytes(), to.bytes(), data, valueWei))
        }

        override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? {
            val handle = handle() ?: return null
            return callOutcome(handle.reads().callDetailed(from.bytes(), to.bytes(), data, valueWei, "latest", null))
        }

        override fun sendRaw(raw: ByteArray): SendAnswer {
            val handle = handle() ?: return SendAnswer.Unavailable("the light client is not running")
            return sendAnswer(handle.reads().sendRawTransactionDetailed(raw))
        }

        override fun receipt(hash: String): String? =
            handle()?.reads()?.getTransactionReceipt(hash.removePrefix("0x").fromHex())

        override fun setLogIndexConfig(configJson: String): Boolean =
            handle()?.setLogIndexConfig(configJson) ?: false

        override fun logIndexStatus(): String? = handle()?.logIndexStatusJson()

        override fun logs(filterJson: String): String? = handle()?.reads()?.getLogs(filterJson)

        override fun setActivityWatch(configJson: String): Boolean = handle()?.setActivityWatch(configJson) ?: false

        override fun addressActivity(afterBlock: Long): String? = handle()?.addressActivityJson(afterBlock)

        // FINALIZED only: PEER_HEAD would be a peer's claim, and AUTO falls back to it.
        override fun resolveName(name: String): NameAnswer {
            val handle = handle()
                ?: return NameAnswer(null, -1, verified = false, error = "the light client is not running")
            val ens = handle.ens()
                ?: return NameAnswer(null, -1, verified = false, error = "this network has no name registry")
            return nameAnswer(ens.resolveAddress(name, EnsRoot.FINALIZED))
        }
    }

    companion object {
        /** How often the verified reads poll the engine, as on the JVM targets. */
        internal val POLL_INTERVAL = 4.seconds

        /** A storage word's hex digits. */
        private const val WORD_HEX_DIGITS = 64

        /** How long one status read serves other pollers: well under the fastest poll (1 s). */
        private val SNAPSHOT_REUSE = 500.milliseconds

        /** Faster than balances: a progress display that moves in 4 s steps looks stuck. */
        private val PROGRESS_POLL_INTERVAL = 1.seconds

        /** Engine log lines a failed start reports: the last ones, where it says why. */
        private const val ENGINE_LOG_LINES = 40

        /** How often a debug build drains the engine's log; its ring holds hours of lines. */
        private val ENGINE_LOG_INTERVAL = 1.seconds

        /** How long [shutdown] waits for a boot in progress to stop its own engine. */
        private val SHUTDOWN_WAIT = 15.seconds

        /**
         * A node for [network] with its state under [dataDir] (a path in the app's own
         * container), booting off the caller's thread: creating the engine and starting it
         * block, and none of it may run on the main thread. [debugLog] takes the adapters'
         * diagnostics and, while the engine runs, the engine's own log ([EngineLogPump]): a
         * debug build's log, null in a release build, which leaves the engine's log undrained;
         * public chain data only.
         * [lastBalances] is the file the balances last verified are kept in, to be shown as such
         * after a restart until a head can be read ([LastBalancesFile]); null keeps them for the
         * process only. [apiLog] takes each request the adapters make of the engine
         * ([LoggedChain]): the Debug tab's log, in memory only; null records none. An engine that
         * fails to start gets a node whose balances and light client say so.
         */
        fun start(
            network: Network,
            dataDir: String,
            lastBalances: String? = null,
            debugLog: ((String) -> Unit)? = null,
            apiLog: LogSink? = null,
        ): MyotisNode {
            val node = MyotisNode(network, lastBalances, debugLog, apiLog)
            node.bootJob = node.scope.launch {
                try {
                    node.boot(network, dataDir)
                } catch (e: Throwable) {
                    // A refused config or a runtime that would not come up: no engine, said so.
                    report("the engine failed to boot: $e")
                    node.state.store(State.Down)
                    // Nothing left to pause or resume: release the control work with the boot.
                    node.scope.cancel()
                }
            }
            return node
        }
    }
}

/**
 * Why there is no light client, in the device's log: the one fact a user's "No light client"
 * cannot tell a developer. Engine state only; no key material is anywhere near this path.
 */
private fun report(why: String) = log("Serotine: no light client: $why")

/**
 * [line] in the device's log: NSLog, since println's stdout reaches no one unless Xcode runs the
 * app. The line is the format itself, its `%` escaped, so nothing crosses NSLog's C varargs: with
 * `NSLog("%@", line)` the debug app died at launch on the simulator (#129's CI).
 */
private fun log(line: String) = NSLog(line.replace("%", "%%"))

/** Creates [path] and its parents; an existing directory is fine, a failure the engine's to tell. */
@OptIn(ExperimentalForeignApi::class)
private fun createDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}
