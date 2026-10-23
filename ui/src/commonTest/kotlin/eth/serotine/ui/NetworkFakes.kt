package eth.serotine.ui

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NetworkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [NetworkSettings] in memory, Sepolia alone on (a debug build's default, what the tests' accounts
 * are on) unless a test sets [on]; [failSaves] makes it fail.
 */
internal class MemoryNetworkSettings(initial: Set<Network> = setOf(Network.SEPOLIA)) : NetworkSettings {
    val on = MutableStateFlow(initial)
    var failSaves = false

    override fun observe(): Flow<Set<Network>> = on

    override suspend fun setEnabled(network: Network, on: Boolean): Boolean {
        if (failSaves) return false
        this.on.value = if (on) this.on.value + network else this.on.value - network
        return true
    }
}
