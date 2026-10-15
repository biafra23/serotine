package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for the embedded light client's own state: how far it is from serving verified
 * reads, for the user to see while it starts. :data implements it over the Myotis engine
 * (`eth.serotine.data.engine`), on the one network the app works on.
 */
interface LightClient {
    /** The light client's state, re-emitted when it changes. Never fails. */
    fun observeStatus(): Flow<LightClientStatus>
}

/** One state of the light client, in the order a cold start passes through them. */
sealed interface LightClientStatus {

    /** The engine is starting and has no state yet. */
    data object Starting : LightClientStatus

    /**
     * Looking for peers to bootstrap from the trusted checkpoint; [peers] served
     * light-client data in the last minute.
     */
    data class Bootstrapping(val peers: Int) : LightClientStatus

    /**
     * Verifying the sync-committee handovers from the checkpoint to today: [done] of [total]
     * periods (about 27 hours of chain each) so far; [peers] served light-client data in the
     * last minute. [total] is 0 while the distance is not known yet.
     */
    data class Syncing(val done: Long, val total: Long, val peers: Int) : LightClientStatus

    /**
     * Synced to the head, collecting the beacon-attested state roots reads are verified
     * against: [known] of the [needed] before the first verified read.
     */
    data class CatchingUp(val known: Int, val needed: Int) : LightClientStatus

    /** Serving verified reads, at the verified execution head [headBlock]. */
    data class Synced(val headBlock: Long) : LightClientStatus

    /**
     * Sync refused: the trust anchor is [anchorAgePeriods] sync-committee periods old, past
     * the [boundPeriods] weak-subjectivity bound. Nothing is verified until the user decides.
     */
    data class StaleAnchor(val anchorAgePeriods: Long, val boundPeriods: Long) : LightClientStatus

    /** There is no light client on this device, or it failed to start. */
    data object Unavailable : LightClientStatus
}
