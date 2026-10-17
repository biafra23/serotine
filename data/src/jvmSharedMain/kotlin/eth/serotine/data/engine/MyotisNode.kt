package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.TokenBalances
import io.myotis.api.BeaconState
import io.myotis.api.ChainHandle
import io.myotis.api.EngineConfig
import io.myotis.api.LifecycleState
import io.myotis.api.MyotisEngine
import io.myotis.api.ports.DnsServers
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
 * creates it with [start], hands [nativeBalances], [tokenBalances], [addressKinds] and
 * [lightClient] to the AppGraph, and owns it for the life of the process: [pause] and
 * [resume] it around the app being in the background, [shutdown] on exit. Only
 * `io.myotis.api` and the library's default ports are used; nothing below them.
 */
class MyotisNode private constructor() {

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
                engine.shutdownAll()
                ports.close()
            }
        }
        data object Down : State
    }

    @Volatile
    private var state: State = State.Booting

    @Volatile
    private var bootThread: Thread? = null

    /** Runs pause and resume, which block while the engine tears down or warms up networking. */
    private val control = Executors.newSingleThreadExecutor { task ->
        Thread(task, "myotis-control").apply { isDaemon = true }
    }

    private val chain = HandleChain()

    /** Native-currency balances, verified by this node's light client. */
    val nativeBalances: NativeBalances = VerifiedNativeBalances(chain, Dispatchers.IO, POLL_INTERVAL)

    /** What is at an address (an account, a delegated one, a contract), verified here. */
    val addressKinds: AddressKinds = VerifiedAddressKinds(chain, Dispatchers.IO, POLL_INTERVAL)

    /** ERC-20 balances, each a verified call of the token's `balanceOf`. */
    val tokenBalances: TokenBalances = VerifiedTokenBalances(chain, Dispatchers.IO, POLL_INTERVAL)

    /** This node's light client, for the user to watch. */
    val lightClient: LightClient = EngineLightClient(chain, Dispatchers.IO, PROGRESS_POLL_INTERVAL)

    /**
     * Turns the engine's networking off, keeping its state, for while the app is in the
     * background. Returns at once; the pause runs on a background thread.
     */
    fun pause() = onControlThread { it.pause() }

    /** Turns networking back on after [pause]; a warm start. Returns at once. */
    fun resume() = onControlThread { it.resume() }

    private fun onControlThread(action: (ChainHandle) -> Unit) {
        try {
            control.execute { (state as? State.Running)?.handle?.let(action) }
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
        val config = EngineConfig(
            name,
            0, 0, 0, // the network's default EL and discv5 ports; rpcPort is unused, nothing listens
            ports.syncSnapshot().toString(),
            TARGET_SNAP_PEERS,
            true, // strict state freshness
            dataDir.path,
        )
        val handle = try {
            engine.create(config, ports.ports())
        } catch (e: Throwable) {
            // An engine that refuses the config (an anchor mismatch, a bad data directory)
            // still holds the ports' cache writers: release them before giving up.
            engine.shutdownAll()
            ports.close()
            throw e
        }
        val info = engine.availableNetworks().firstOrNull { it.name() == engine.canonicalNetworkName(name) }
        val running = State.Running(engine, ports, handle, info?.clGenesisTime() ?: 0, info?.secondsPerSlot() ?: 0)
        val started = handle.start()
        val kept = synchronized(this) {
            val keep = started && state == State.Booting
            if (state == State.Booting) state = if (keep) running else State.Down
            keep
        }
        // The engine says why only in its own log, which is drained, not printed.
        if (!started) report("the engine refused to start $name:\n" + Myotis.drainLogs(ENGINE_LOG_LINES))
        if (!kept) running.stop()
    }

    private inner class HandleChain : VerifiedChain {
        override fun snapshot(): EngineSnapshot {
            val running = when (val current = state) {
                State.Booting -> return EngineSnapshot.Booting
                State.Down -> return EngineSnapshot.Down
                is State.Running -> current
            }
            val handle = running.handle
            val status = handle.status() ?: return EngineSnapshot.Booting
            // The head block's time from its slot, the consensus rule for an execution
            // payload's timestamp (genesis + slot × seconds per slot): verified data only.
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
    }

    companion object {
        private val POLL_INTERVAL = 4.seconds

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
            val node = MyotisNode()
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

/** The engine's name for this network, as `MyotisEngine.availableNetworks()` lists it. */
private fun Network.engineName(): String = when (this) {
    Network.SEPOLIA -> "sepolia"
}
