package eth.serotine.domain.repository

import eth.serotine.domain.model.NetworkUpgrade
import kotlinx.coroutines.flow.Flow

/**
 * The port for network upgrades this version does not support, as the embedded engine hears
 * peers report them, on the one network the app works on. :data implements it over the
 * Myotis engine's upgrade advisory (`eth.serotine.data.engine`). Peer reports, not verified
 * chain data: what the user is told about them says so, and nothing the wallet verifies
 * depends on them.
 */
interface NetworkUpgrades {
    /**
     * The upgrade peers report right now, or null while they report none; re-emitted when it
     * changes. Peers' evidence comes and goes (a report needs several peer networks behind
     * it), so a null says nothing is reported now, not that no upgrade is coming. A device
     * without an engine, or with one that is down, emits null once. Never fails.
     */
    fun observeUpgrade(): Flow<NetworkUpgrade?>
}
