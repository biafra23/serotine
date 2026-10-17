package eth.serotine.domain.model

/**
 * A network upgrade (hard fork) that peers report and that this version of the app does not
 * support: the embedded engine's upgrade advisory (docs/embedding-myotis.md, *Network
 * upgrades*). It is derived from what peers announce and from nothing the light client
 * verifies, so it is a warning, never chain state: lying peers can raise a false one, but it
 * can never make a wrong answer look right, and nothing in verification reads it.
 *
 * [activationTime] is when the upgrade activates, or activated, in unix seconds; null when no
 * peer announced it (peers are seen on the new rules, but the time is not on the wire).
 * [forkId] is the identifier upgraded peers use once it is active (`0x` and 8 lowercase hex
 * digits); null when unknown. [reportingNetworks] is how many distinct peer networks back the
 * report: the engine counts a source per IPv4 /24 or IPv6 /48, not per peer.
 */
data class NetworkUpgrade(
    val phase: Phase,
    val activationTime: Long?,
    val forkId: String?,
    val reportingNetworks: Int,
) {
    init {
        require(activationTime == null || activationTime > 0) { "an activation time is unix seconds, or null" }
        require(forkId == null || FORK_ID.matches(forkId)) { "a fork id is 0x and 8 lowercase hex digits: $forkId" }
        require(reportingNetworks >= 0) { "a count of peer networks: $reportingNetworks" }
    }

    /** How far along the upgrade is, as peers report it. */
    enum class Phase {
        /** Its activation lies ahead: the app must be updated before then. */
        SCHEDULED,

        /** It has activated: this version can no longer follow the network. */
        ACTIVE,
    }

    /** Whether the upgrade has activated at [now] (unix seconds): reported active, or its time is past. */
    fun hasActivated(now: Long): Boolean = phase == Phase.ACTIVE || activationTime != null && activationTime <= now

    /**
     * Whether [other] reports this same upgrade: the same activation time, or a report that
     * does not know the time (an upgrade first announced for a time, then seen active on peers
     * that do not say when). Another time is another upgrade, or this one moved.
     */
    fun isSameUpgradeAs(other: NetworkUpgrade): Boolean =
        activationTime == null || other.activationTime == null || activationTime == other.activationTime

    /**
     * This report brought up to date by [newer], a later report of the same upgrade: active
     * once either says so, with the time and fork id whichever of the two knows them, and the
     * newer count of reporting networks.
     */
    fun updatedBy(newer: NetworkUpgrade): NetworkUpgrade = NetworkUpgrade(
        phase = if (phase == Phase.ACTIVE || newer.phase == Phase.ACTIVE) Phase.ACTIVE else Phase.SCHEDULED,
        activationTime = newer.activationTime ?: activationTime,
        forkId = newer.forkId ?: forkId,
        reportingNetworks = newer.reportingNetworks,
    )

    private companion object {
        val FORK_ID = Regex("^0x[0-9a-f]{8}$")
    }
}
