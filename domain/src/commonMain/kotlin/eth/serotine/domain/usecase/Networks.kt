package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.LightClientControl
import eth.serotine.domain.repository.LightClientRun
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The networks that are on in this wallet, in [Network] order (the order of every list on
 * screen), re-emitted when one is turned on or off.
 */
class ObserveEnabledNetworks(private val settings: NetworkSettings) {
    operator fun invoke(): Flow<List<Network>> = settings.observe()
        .map { on -> Network.entries.filter { it in on } }
        .distinctUntilChanged()
}

/** Turns a network on or off (docs/networks.md, *Networks on and off*); false when that could not be saved. */
class SetNetworkEnabled(private val settings: NetworkSettings) {
    suspend operator fun invoke(network: Network, on: Boolean): Boolean = settings.setEnabled(network, on)
}

/**
 * The screens that need a network's light client while they are open, though no account of the
 * wallet is on it (Watch checking an address, Add token reading a contract): one per process,
 * shared by the screens and [FollowNetworks]. A hold keeps its network running, if it is on,
 * until it is released.
 */
class NetworkDemand {
    private val holds = MutableStateFlow<Map<Network, Int>>(emptyMap())

    /** The networks some screen holds now, re-emitted when that changes. */
    fun observe(): Flow<Set<Network>> = holds.map { it.filterValues { count -> count > 0 }.keys }.distinctUntilChanged()

    /** Holds [network] until the [NetworkHold] returned is released (once; a second release does nothing). */
    fun hold(network: Network): NetworkHold {
        holds.update { it + (network to (it[network] ?: 0) + 1) }
        return NetworkHold { holds.update { it + (network to ((it[network] ?: 1) - 1)) } }
    }
}

/** A screen's hold on a network ([NetworkDemand.hold]); [release] ends it, once. */
class NetworkHold internal constructor(private val onRelease: () -> Unit) {
    private var released = false

    /** Ends the hold; a second call does nothing. */
    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/** Holds a network's light client for a screen while it is open ([NetworkDemand]). */
class HoldNetwork(private val demand: NetworkDemand) {
    operator fun invoke(network: Network): NetworkHold = demand.hold(network)
}

/**
 * Keeps each network's light client doing what the wallet needs of it (docs/networks.md, *The
 * engine host*), for as long as it runs (the hosts run it while the engine does, never `App`):
 *
 * - a network turned off ([NetworkSettings]) is [LightClientRun.OFF] at once;
 * - one that is on runs while an account of the wallet is on it, or a screen holds it
 *   ([NetworkDemand]); an account list that cannot be read counts as having accounts on every
 *   network that is on, as nothing says otherwise;
 * - one that is on and needed by nothing is [LightClientRun.IDLE], [idleAfter] after it last was
 *   needed (an account removed, a screen closed): a screen opened again meanwhile keeps it
 *   running. One never needed since the start is idle at once.
 */
class FollowNetworks(
    private val settings: NetworkSettings,
    private val accounts: AccountRepository,
    private val demand: NetworkDemand,
    private val control: LightClientControl,
    private val idleAfter: Duration = 30.seconds,
) {
    /** Runs until cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend operator fun invoke(): Unit = coroutineScope {
        val wanted = combine(settings.observe(), accounts.observeAccounts(), demand.observe()) { on, list, held ->
            Network.entries.associateWith { network ->
                val needed = network in held || when (list) {
                    is AccountList.Loaded -> list.accounts.any { it.network == network }
                    is AccountList.Unreadable -> true
                }
                when {
                    network !in on -> LightClientRun.OFF
                    needed -> LightClientRun.RUN
                    else -> LightClientRun.IDLE
                }
            }
        }
        Network.entries.forEach { network ->
            launch {
                // What the network was last told: it is told again only what changes it.
                var told: LightClientRun? = null
                wanted.map { it.getValue(network) }.distinctUntilChanged().collectLatest { run ->
                    if (run == told) return@collectLatest
                    // Cancelled by the next change: a network needed again within the delay keeps running.
                    if (run == LightClientRun.IDLE && told == LightClientRun.RUN) delay(idleAfter)
                    control.set(network, run)
                    told = run
                }
            }
        }
    }
}
