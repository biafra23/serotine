package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
