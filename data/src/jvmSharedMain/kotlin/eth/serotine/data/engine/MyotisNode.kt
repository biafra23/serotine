package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.LightClient
import io.myotis.api.BeaconState
import io.myotis.api.ChainHandle
import io.myotis.api.EngineConfig
import io.myotis.api.MyotisEngine
import io.myotis.api.SyncState
import io.myotis.api.ports.DnsServers
import io.myotis.engine.Myotis
import io.myotis.engine.ports.DefaultEnginePorts
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * The embedded Myotis engine of this process (docs/embedding-myotis.md): one engine hosting
 * one chain, and the verified reads the adapters make through it. A host's composition root
 * creates it with [start], hands [nativeBalances] to the AppGraph, and owns it for the life
 * of the process, calling [shutdown] on exit. Only `io.myotis.api` and the library's default
 * ports are used; nothing below them.
 */
class MyotisNode private constructor() {

    private sealed interface State {
        data object Booting : State
        class Running(val engine: MyotisEngine, val ports: DefaultEnginePorts, val handle: ChainHandle) : State {
            fun stop() {
                engine.shutdownAll()
                ports.close()
            }
        }
        data object Down : State
    }

    @Volatile
    private var state: State = State.Booting

    private val chain = HandleChain()

    /** Native-currency balances, verified by this node's light client. */
    val nativeBalances: NativeBalances = VerifiedNativeBalances(chain, Dispatchers.IO, POLL_INTERVAL)

    /** This node's light client, for the user to watch while it starts. */
    val lightClient: LightClient = EngineLightClient(chain, Dispatchers.IO, PROGRESS_POLL_INTERVAL)

    /**
     * Stops the chain and the engine and releases the files they hold; idempotent. Never
     * waits for a boot in progress: a boot that finishes after this stops its own engine.
     */
    fun shutdown() {
        val running = synchronized(this) {
            val current = state
            state = State.Down
            current as? State.Running
        }
        running?.stop()
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
        val running = State.Running(engine, ports, engine.create(config, ports.ports()))
        val started = running.handle.start()
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
        override fun status(): ChainStatus {
            val handle = when (val current = state) {
                State.Booting -> return ChainStatus.Syncing
                State.Down -> return ChainStatus.Down
                is State.Running -> current.handle
            }
            val reads = handle.reads() ?: return ChainStatus.Syncing
            return when (reads.syncState()) {
                SyncState.SYNCED -> reads.headBlockNumber()?.let(ChainStatus::Synced) ?: ChainStatus.Syncing
                SyncState.STALE_ANCHOR -> ChainStatus.StaleAnchor
                SyncState.SYNCING, SyncState.CATCHING_UP, null -> ChainStatus.Syncing
            }
        }

        override fun beacon(): BeaconSnapshot {
            val handle = when (val current = state) {
                State.Booting -> return BeaconSnapshot.Booting
                State.Down -> return BeaconSnapshot.Down
                is State.Running -> current.handle
            }
            val beacon = handle.beaconStatus() ?: return BeaconSnapshot.Booting
            return BeaconSnapshot.Running(
                phase = when (beacon.state()) {
                    BeaconState.SYNCING -> BeaconSnapshot.Phase.SYNCING
                    BeaconState.CATCHING_UP -> BeaconSnapshot.Phase.CATCHING_UP
                    BeaconState.SYNCED -> BeaconSnapshot.Phase.SYNCED
                    BeaconState.STALE_ANCHOR -> BeaconSnapshot.Phase.STALE_ANCHOR
                    BeaconState.STARTING, null -> BeaconSnapshot.Phase.STARTING
                },
                bootstrapped = beacon.bootstrapped(),
                currentPeriod = beacon.currentPeriod(),
                targetPeriod = beacon.targetPeriod(),
                servingPeers = beacon.servedPeersLastMinute(),
                knownStateRoots = beacon.knownStateRoots(),
                fillThreshold = beacon.fillThreshold(),
                // The head reads are verified at, the one balances are labelled with.
                headBlock = handle.reads()?.takeIf { it.syncState() == SyncState.SYNCED }?.headBlockNumber(),
                wsBoundPeriods = beacon.wsBoundPeriods(),
            )
        }

        // At the head's own number, not "latest": the head may move between the status read
        // and this one, and the reading is labelled with the block it was proven at.
        override fun balanceWei(address: Address, blockNumber: Long): String? =
            (state as? State.Running)?.handle?.reads()?.getBalance(address.toBytes(), "0x" + blockNumber.toString(16))
    }

    companion object {
        private val POLL_INTERVAL = 4.seconds

        /** Faster than balances: a progress display that moves in 4 s steps looks stuck. */
        private val PROGRESS_POLL_INTERVAL = 1.seconds

        /** Engine log lines a failed start reports. */
        private const val ENGINE_LOG_LINES = 40

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
         * start gets a node whose balances say so ([eth.serotine.domain.repository.BalanceReading.Reason.NO_ENGINE]).
         */
        fun start(network: Network, dataDir: File, dnsServers: (() -> List<String>)? = null): MyotisNode {
            val node = MyotisNode()
            thread(name = "myotis-boot", isDaemon = true) {
                try {
                    node.boot(network, dataDir, dnsServers?.let { servers -> DnsServers { servers() } })
                } catch (e: Throwable) {
                    // A native library that loads but cannot run (a missing symbol, an ABI
                    // mismatch) fails here with an Error: no engine, said as such.
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

private fun Address.toBytes(): ByteArray =
    ByteArray(20) { i -> hex.substring(2 + 2 * i, 4 + 2 * i).toInt(16).toByte() }
