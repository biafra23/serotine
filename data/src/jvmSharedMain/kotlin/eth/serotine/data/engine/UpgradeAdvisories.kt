package eth.serotine.data.engine

import eth.serotine.domain.model.NetworkUpgrade
import io.myotis.api.UpgradeAdvisory
import io.myotis.api.UpgradePhase

/**
 * The engine's advisory as the domain's [NetworkUpgrade], or null for one with no phase (the
 * engine's own parsers read that as none). Never throws: the advisory arrives in the same
 * status snapshot as everything the light client and the balances read, so a field out of
 * shape becomes unknown rather than failing that poll. The API's "unknown" values, a time of
 * 0 and the fork id `0x00000000`, become null.
 */
internal fun UpgradeAdvisory.toNetworkUpgrade(): NetworkUpgrade? {
    val phase = when (phase()) {
        UpgradePhase.SCHEDULED -> NetworkUpgrade.Phase.SCHEDULED
        UpgradePhase.ACTIVE -> NetworkUpgrade.Phase.ACTIVE
        null -> return null
    }
    return NetworkUpgrade(
        phase = phase,
        activationTime = activationTime().takeIf { it > 0 },
        forkId = forkId()?.lowercase()?.takeIf { FORK_ID.matches(it) && it != UNKNOWN_FORK_ID },
        reportingNetworks = observedPeers().coerceAtLeast(0),
    )
}

/** `0x` and 8 hex digits: an EIP-2124 fork hash or a consensus fork digest. */
private val FORK_ID = Regex("^0x[0-9a-f]{8}$")

/** What the API reports for a fork id this build cannot compute (a blob-parameter-only fork). */
private const val UNKNOWN_FORK_ID = "0x00000000"
