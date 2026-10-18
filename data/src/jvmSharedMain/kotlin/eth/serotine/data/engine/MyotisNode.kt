package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.safe.SafeDeployments
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionInputs
import io.myotis.api.BeaconState
import io.myotis.api.ChainHandle
import io.myotis.api.EngineConfig
import io.myotis.api.EnsRoot
import io.myotis.api.LifecycleState
import io.myotis.api.MyotisEngine
import io.myotis.api.ports.DnsServers
import io.myotis.api.ports.EnginePorts
import io.myotis.engine.Myotis
import io.myotis.engine.ports.DefaultEnginePorts
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * The embedded Myotis engine of this process (docs/embedding-myotis.md): one engine hosting
 * one chain, and the verified reads the adapters make through it. A host's composition root
 * creates it with [start], hands [nativeBalances], [tokenBalances], [tokenMetadata],
 * [priceFeeds], [addressKinds], [lightClient] and [networkUpgrades] to the AppGraph, and for
 * sending [transactionInputs], [broadcaster], [receipts] and [names], and for a Safe's
 * transactions [safeApprovals], and owns it for the life of the process: [pause] and [resume] it
 * around the app being in the
 * background, [shutdown] on exit. Only `io.myotis.api` and the library's default ports are
 * used; nothing below them.
 */
class MyotisNode private constructor(network: Network) {

    private sealed interface State {
        data object Booting : State
        class Running(
            val engine: MyotisEngine,
            val ports: DefaultEnginePorts,
            val handle: ChainHandle,
            /** The network's beacon genesis (unix seconds) and slot length, from the engine. */
            val genesisTime: Long,
            val secondsPerSlot: Int,
        ) : State {
            fun stop() {
                try {
                    engine.shutdownAll()
                } finally {
                    ports.close() // the cache writers close whatever the engine does
                }
            }
        }
        data object Down : State
    }

    @Volatile
    private var state: State = State.Booting

    @Volatile
    private var bootThread: Thread? = null

    /**
     * Whether the host last asked for networking off. A pause can come before the boot has
     * finished (the app backgrounded right after launch); the boot applies it then.
     */
    @Volatile
    private var pauseWanted = false

    /** Runs pause and resume, which block while the engine tears down or warms up networking. */
    private val control = Executors.newSingleThreadExecutor { task ->
        Thread(task, "myotis-control").apply { isDaemon = true }
    }

    private val chain = HandleChain()

    /** Native-currency balances, verified by this node's light client. */
    val nativeBalances: NativeBalances = VerifiedNativeBalances(chain, Dispatchers.IO, POLL_INTERVAL)

    /** What is at an address (an account, a delegated one, a Safe, a contract), verified here. */
    val addressKinds: AddressKinds =
        VerifiedAddressKinds(chain, Dispatchers.IO, POLL_INTERVAL, SafeDeployments.of(network))

    /** What a token contract says about itself, as verified calls. */
    val tokenMetadata: TokenMetadata = VerifiedTokenMetadata(chain, Dispatchers.IO, POLL_INTERVAL)

    /** Prices, each a verified call of one of the network's pinned Chainlink feeds. */
    val priceFeeds: PriceFeeds = VerifiedPriceFeeds(chain, Dispatchers.IO, POLL_INTERVAL, ChainlinkFeeds.of(network))

    /** ERC-20 balances, each a verified call of the token's `balanceOf`. */
    val tokenBalances: TokenBalances = VerifiedTokenBalances(chain, Dispatchers.IO, POLL_INTERVAL)

    /** This node's light client, for the user to watch. */
    val lightClient: LightClient = EngineLightClient(chain, Dispatchers.IO, PROGRESS_POLL_INTERVAL)

    /** Network upgrades this build does not support, as peers report them to this node. */
    val networkUpgrades: NetworkUpgrades = EngineNetworkUpgrades(chain, Dispatchers.IO, POLL_INTERVAL)

    /** A transaction's nonce, fees, gas and verified simulation, from this node's verified reads. */
    val transactionInputs: TransactionInputs = EngineTransactionInputs(chain, Dispatchers.IO)

    /** Gossips a signed transaction to this node's peers (the engine never signs). */
    val broadcaster: Broadcaster = EngineBroadcaster(chain, Dispatchers.IO)

    /** A Safe's owners' approvals on chain (`approvedHashes`), each a verified call. */
    val safeApprovals: SafeApprovals = VerifiedSafeApprovals(chain, Dispatchers.IO)

    /** A sent transaction's verified receipt and its block's finality. */
    val receipts: ReceiptTracker = EngineReceipts(chain, Dispatchers.IO, POLL_INTERVAL)

    /** ENS names, resolved against beacon-finalized state. */
    val names: NameResolver = EngineNames(chain, Dispatchers.IO)

    /**
     * Turns the engine's networking off, keeping its state, for while the app is in the
     * background. Returns at once; the pause runs on a background thread. Asked for while the
     * engine is still starting, it is applied once the engine runs.
     */
    fun pause() {
        pauseWanted = true
        onControlThread { it.pause() }
    }

    /** Turns networking back on after [pause]; a warm start. Returns at once. */
    fun resume() {
        pauseWanted = false
        onControlThread { it.resume() }
    }

    private fun onControlThread(action: (ChainHandle) -> Unit) {
        try {
            control.execute {
                try {
                    (state as? State.Running)?.handle?.let(action)
                } catch (e: Throwable) {
                    // Throwable: the call crosses JNA. Uncaught on this thread it would end the
                    // process; the engine keeps its last networking state instead.
                    System.err.println("Serotine: the engine's networking could not be switched: $e")
                }
            }
        } catch (e: RejectedExecutionException) {
            // Shut down already: nothing left to pause or resume.
        }
    }

    /**
     * Stops the chain and the engine and releases the files they hold; idempotent. A boot
     * still in progress stops its own engine when it finishes; this waits for that, up to
     * [SHUTDOWN_WAIT_MS], so a process that exits right after does not cut it off mid-start.
     * Blocks: never call it on a UI thread.
     */
    fun shutdown() {
        val running = synchronized(this) {
            val current = state
            state = State.Down
            current as? State.Running
        }
        running?.stop()
        bootThread?.takeIf { it.isAlive && it != Thread.currentThread() }?.join(SHUTDOWN_WAIT_MS)
        control.shutdown()
    }

    private fun boot(network: Network, dataDir: File, dnsServers: DnsServers?) {
        if (state != State.Booting) return // shut down before the boot thread got here
        if (!Myotis.isAvailable()) {
            report("no engine library loads on this device")
            synchronized(this) { if (state == State.Booting) state = State.Down }
            return
        }
        val engine = Myotis.newEngine()
        val name = network.engineName()
        dataDir.mkdirs()
        val dir = dataDir.toPath()
        // One directory for the default ports and the engine: the Rust engine keeps its peer
        // caches and sync snapshot under EngineConfig.dataDir, in the ports' file names.
        val ports = Myotis.defaultPorts(engine, dir, dir, name, dnsServers)
        // No CCIP-Read gateway: an off-chain ENS answer (EIP-3668) comes from an HTTP gateway the
        // resolver names, an off-chain source no ruling allows, and the request would tell it the
        // name and this device's address. With none, the engine refuses such a name, and says so.
        val defaults = ports.ports()
        val enginePorts = EnginePorts(
            defaults.keyStore(),
            defaults.peerCache(),
            defaults.clPeerCache(),
            defaults.dnsServers(),
            null,
            defaults.logger(),
            defaults.clock(),
        )
        val config = EngineConfig(
            name,
            0, 0, 0, // the network's default EL and discv5 ports; rpcPort is unused, nothing listens
            ports.syncSnapshot().toString(),
            TARGET_SNAP_PEERS,
            true, // strict state freshness
            dataDir.path,
        )
        val handle = try {
            engine.create(config, enginePorts)
        } catch (e: Throwable) {
            // An engine that refuses the config (an anchor mismatch, a bad data directory)
            // still holds the ports' cache writers: release them before giving up.
            releaseAfter(e, engine, ports)
            throw e
        }
        // From here until the running state is published, this boot owns the engine: whatever
        // fails (a native start that throws rather than answering false, reading the network's
        // timing, draining the log) releases it, since shutdown() finds nothing to stop yet.
        var owned = true
        try {
            val info = engine.availableNetworks().firstOrNull { it.name() == engine.canonicalNetworkName(name) }
            val running = State.Running(engine, ports, handle, info?.clGenesisTime() ?: 0, info?.secondsPerSlot() ?: 0)
            val started = handle.start()
            // The engine says why only in its own log, which is drained, not printed: drained
            // before anything stops the engine, so the refusal comes first, ahead of shutdown noise.
            val refusal = if (started) null else Myotis.drainLogs(ENGINE_LOG_LINES)
            val kept = synchronized(this) {
                val keep = started && state == State.Booting
                if (state == State.Booting) state = if (keep) running else State.Down
                keep
            }
            owned = false
            if (!kept) running.stop()
            // A pause asked for during the boot, decided on the control thread: every pause and
            // resume the host asks for from here on queues there too, so the last one asked for
            // is the state the engine ends in.
            if (kept) onControlThread { if (pauseWanted) it.pause() }
            refusal?.let { report("the engine refused to start $name:\n$it") }
        } catch (e: Throwable) {
            if (owned) releaseAfter(e, engine, ports)
            throw e
        }
    }

    /**
     * Stops [engine] and closes [ports] after [cause]: a cleanup that fails too is added to
     * [cause] instead of hiding it, and the ports close whatever the engine does.
     */
    private fun releaseAfter(cause: Throwable, engine: MyotisEngine, ports: DefaultEnginePorts) {
        try {
            engine.shutdownAll()
        } catch (t: Throwable) {
            cause.addSuppressed(t)
        }
        try {
            ports.close()
        } catch (t: Throwable) {
            cause.addSuppressed(t)
        }
    }

    private inner class HandleChain : TransactingChain {

        /**
         * The last snapshot read and when (System.nanoTime). Every adapter, and each of its
         * subscribers, polls on its own: those that ask within [SNAPSHOT_REUSE_NS] of one read
         * share it, so they cost the engine one status call, not one each.
         */
        @Volatile
        private var lastRead: TimedSnapshot? = null

        override fun snapshot(): EngineSnapshot {
            val now = System.nanoTime()
            lastRead?.let { if (now - it.atNanos < SNAPSHOT_REUSE_NS) return it.snapshot }
            return read().also { lastRead = TimedSnapshot(now, it) }
        }

        private fun read(): EngineSnapshot {
            val running = when (val current = state) {
                State.Booting -> return EngineSnapshot.Booting
                State.Down -> return EngineSnapshot.Down
                is State.Running -> current
            }
            val handle = running.handle
            val status = handle.status() ?: return EngineSnapshot.Booting
            // The head block's time from its slot, the consensus rule for an execution
            // payload's timestamp (genesis + slot × seconds per slot): verified data only.
            // To check: a storage proof's optimisticSlot is the light client's signature slot,
            // about one past the attested head (see storageProofBlock). If BeaconStatus's is that
            // slot too, this time reads a slot (12 s) late; its API does not say which it is.
            val slot = handle.beaconStatus()?.optimisticSlot() ?: 0
            val headTimestamp = if (slot > 0 && running.genesisTime > 0 && running.secondsPerSlot > 0) {
                running.genesisTime + slot * running.secondsPerSlot
            } else {
                null
            }
            return EngineSnapshot.Running(
                phase = when (status.beaconState()) {
                    BeaconState.SYNCING -> EngineSnapshot.Phase.SYNCING
                    BeaconState.CATCHING_UP -> EngineSnapshot.Phase.CATCHING_UP
                    BeaconState.SYNCED -> EngineSnapshot.Phase.SYNCED
                    BeaconState.STALE_ANCHOR -> EngineSnapshot.Phase.STALE_ANCHOR
                    BeaconState.STARTING, null -> EngineSnapshot.Phase.STARTING
                },
                paused = status.lifecycle() == LifecycleState.PAUSED,
                syncStartPeriod = status.syncStartPeriod(),
                syncCurrentPeriod = status.syncCurrentPeriod(),
                syncTargetPeriod = status.syncTargetPeriod(),
                huntingLightClientServers = status.lcHunting(),
                proofPeers = status.snapServingPeers(),
                headBlock = status.optimisticBlockNumber(),
                headTimestamp = headTimestamp,
                headAgeMs = status.verifiedHeadAgeMs(),
                wsBoundPeriods = status.wsBoundPeriods(),
                upgrade = status.upgradeAdvisory()?.toNetworkUpgrade(),
                finalizedBlock = status.finalizedBlockNumber(),
            )
        }

        // requestAccount, not VerifiedReads.getBalance: it returns the verdict and the block
        // the proof anchors to, so a reading is labelled with where it was proven. (A block
        // number passed to getBalance is answered from the current head anyway.)
        override fun proveBalance(address: Address): ProvenBalance? {
            val handle = (state as? State.Running)?.handle ?: return null
            val result = handle.requestAccount(address.hex)
            if (result.failReason() != null || result.verifyMethod() == null) return null
            // A proven absence is a proven zero.
            return ProvenBalance(if (result.exists()) result.balanceWei() ?: return null else "0", result.blockNumber())
        }

        // The code hash comes with the account proof, labelled with its block; the code itself
        // from the verified reads; provenCode decides what of it is proven.
        override fun proveCode(address: Address): ProvenCode? {
            val handle = (state as? State.Running)?.handle ?: return null
            val account = handle.requestAccount(address.hex)
            if (account.failReason() != null || account.verifyMethod() == null) return null
            return provenCode(account.exists(), account.codeHashHex(), account.blockNumber()) {
                handle.reads().getCode(address.hex.removePrefix("0x").fromHex(), "latest")
            }
        }

        override fun call(to: Address, data: ByteArray): CallOutcome? {
            val handle = (state as? State.Running)?.handle ?: return null
            val contract = to.hex.removePrefix("0x").fromHex()
            return callOutcome(handle.reads().callDetailed(null, contract, data, null, "latest", null))
        }

        // Accepted only with a verified storage proof tied to the beacon chain and a verdict, as
        // balances are; a proven empty slot is a proven zero. Labelled as storageProofBlock says:
        // with the block the proof anchors to once it matched an attested slot from the finalized
        // one up to the optimistic head (which on a live engine is the attested slot just behind
        // the result's optimistic slot, its signature slot), or that a header-chain walk proved.
        override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
            val handle = (state as? State.Running)?.handle ?: return null
            val result = handle.getStorageProof(address.hex, slot, holder?.hex)
            if (result.failReason() != null || !result.storageProofValid() || !result.beaconChainVerified()) return null
            val block = storageProofBlock(
                verifyMethod = result.verifyMethod(),
                matchedBeaconSlot = result.matchedBeaconSlot(),
                finalizedSlot = result.finalizedSlot(),
                optimisticSlot = result.optimisticSlot(),
                peerBlockNumber = result.peerBlockNumber(),
                finalizedBlockNumber = result.finalizedBlockNumber(),
                optimisticBlockNumber = result.optimisticBlockNumber(),
            ) ?: return null
            val value = if (result.exists()) result.valueHex()?.removePrefix("0x")?.lowercase() ?: return null else ""
            return value.padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
                ?.let { ProvenSlot(it, block) }
        }

        override fun storageAt(address: Address, slot: ByteArray): String? {
            val handle = (state as? State.Running)?.handle ?: return null
            val contract = address.hex.removePrefix("0x").fromHex()
            val word = handle.reads().getStorageAt(contract, slot, "latest") ?: return null
            return word.toHex().padStart(WORD_HEX_DIGITS, '0').takeIf { it.length == WORD_HEX_DIGITS }
        }

        // "pending": the verified count plus the sends this node made that are not mined yet,
        // so a second transfer right after a first does not reuse its nonce.
        override fun pendingNonce(address: Address): Long? {
            val handle = (state as? State.Running)?.handle ?: return null
            return handle.reads().getTransactionCount(address.bytes(), "pending")
        }

        override fun minedNonce(address: Address): Long? {
            val handle = (state as? State.Running)?.handle ?: return null
            return handle.reads().getTransactionCount(address.bytes(), "latest")
        }

        override fun feeHistory(blocks: Int, percentiles: DoubleArray): String? {
            val handle = (state as? State.Running)?.handle ?: return null
            return handle.reads().feeHistory(blocks.toLong(), "latest", percentiles)
        }

        override fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer {
            val handle = (state as? State.Running)?.handle ?: return EstimateAnswer.Unavailable
            return estimateAnswer(handle.reads().estimateGasDetailed(from.bytes(), to.bytes(), data, valueWei))
        }

        override fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome? {
            val handle = (state as? State.Running)?.handle ?: return null
            val result = handle.reads().callDetailed(from.bytes(), to.bytes(), data, valueWei, "latest", null)
            return callOutcome(result)
        }

        override fun sendRaw(raw: ByteArray): SendAnswer {
            val handle = (state as? State.Running)?.handle
                ?: return SendAnswer.Unavailable("the light client is not running")
            return sendAnswer(handle.reads().sendRawTransactionDetailed(raw))
        }

        override fun receipt(hash: String): String? {
            val handle = (state as? State.Running)?.handle ?: return null
            return handle.reads().getTransactionReceipt(hash.removePrefix("0x").fromHex())
        }

        // FINALIZED only: PEER_HEAD would be a peer's claim, and AUTO falls back to it.
        override fun resolveName(name: String): NameAnswer {
            val handle = (state as? State.Running)?.handle
                ?: return NameAnswer(null, -1, verified = false, error = "the light client is not running")
            return nameAnswerOf { handle.ens().resolveAddress(name, EnsRoot.FINALIZED) }
        }
    }

    private class TimedSnapshot(val atNanos: Long, val snapshot: EngineSnapshot)

    companion object {
        private val POLL_INTERVAL = 4.seconds

        /** A storage word's hex digits. */
        private const val WORD_HEX_DIGITS = 64

        /** How long one status read serves other pollers: well under the fastest poll (1 s). */
        private const val SNAPSHOT_REUSE_NS = 500_000_000L

        /** Faster than balances: a progress display that moves in 4 s steps looks stuck. */
        private val PROGRESS_POLL_INTERVAL = 1.seconds

        /** Engine log lines a failed start reports. */
        private const val ENGINE_LOG_LINES = 40

        /** How long [shutdown] waits for a boot in progress to stop its own engine. */
        private const val SHUTDOWN_WAIT_MS = 15_000L

        /** Snap peers the engine keeps for state proofs; the library guide's mobile setting. */
        private const val TARGET_SNAP_PEERS = 3

        /**
         * A node for [network] with its state under [dataDir], booting on a background thread:
         * loading the native library, creating the engine and starting it all block, and none
         * of it may run on a caller's main thread. [dnsServers] returns the host's resolver
         * IPs for EIP-1459 peer discovery, which a mobile host must supply (null where the
         * engine reads the system's); a plain function, so no Myotis type leaves this module.
         *
         * A device without an engine — an Android ABI the library does not ship, a desktop
         * without the host library on `java.library.path` — or one whose engine fails to
         * start gets a node whose balances and light client say so.
         */
        fun start(network: Network, dataDir: File, dnsServers: (() -> List<String>)? = null): MyotisNode {
            val node = MyotisNode(network)
            node.bootThread = thread(name = "myotis-boot", isDaemon = true) {
                try {
                    node.boot(network, dataDir, dnsServers?.let { servers -> DnsServers { servers() } })
                } catch (e: Throwable) {
                    // A native library that loads but cannot run (a missing symbol, an ABI
                    // mismatch) fails with an Error, a refused config with an exception: no
                    // engine, said as such.
                    report("the engine failed to boot: $e")
                    node.shutdown()
                }
            }
            return node
        }
    }
}

/**
 * Why there is no light client, on stderr (logcat's System.err on Android): the one fact a
 * user's "No light client" cannot tell a developer. Engine state only; no key material is
 * anywhere near this path.
 */
private fun report(why: String) = System.err.println("Serotine: no light client: $why")

/** The 20 bytes the engine's reads take for an address. */
private fun Address.bytes(): ByteArray = hex.removePrefix("0x").fromHex()

/** The engine's name for this network, as `MyotisEngine.availableNetworks()` lists it. */
private fun Network.engineName(): String = when (this) {
    Network.SEPOLIA -> "sepolia"
}
