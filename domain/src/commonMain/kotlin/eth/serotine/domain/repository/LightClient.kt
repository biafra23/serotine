package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for the embedded light client's own state: how far it is from serving verified
 * reads, for the user to see. :data implements it over the Myotis engine
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
     * Bootstrapping from the trusted checkpoint; [lookingForServers] while no peer serving
     * light-client data has been found.
     */
    data class Bootstrapping(val lookingForServers: Boolean) : LightClientStatus

    /**
     * Verifying the sync-committee handovers from the checkpoint to today: [done] of [total]
     * periods (about 27 hours of chain each). [total] is 0 while the distance is unknown.
     */
    data class CatchingUp(val done: Long, val total: Long) : LightClientStatus

    /**
     * Synced, but without a fresh verified head to read at: none built yet ([headAgeSeconds]
     * null), or the last one is [headAgeSeconds] old.
     */
    data class WaitingForHead(val headAgeSeconds: Long?) : LightClientStatus

    /**
     * Synced to [headBlock], but no connected peer serves the state proofs reads need.
     * [headTimestamp] is when that block was produced (unix seconds), null when unknown.
     */
    data class WaitingForProofPeers(val headBlock: Long, val headTimestamp: Long?) : LightClientStatus

    /**
     * Serving verified reads at the fresh verified head [headBlock], produced at
     * [headTimestamp] (unix seconds; null when unknown).
     */
    data class Synced(val headBlock: Long, val headTimestamp: Long?) : LightClientStatus

    /** Networking is off while the app is in the background; it resumes when the app returns. */
    data object Sleeping : LightClientStatus

    /**
     * Sync refused: the trust anchor is [anchorAgePeriods] sync-committee periods old, past
     * the [boundPeriods] weak-subjectivity bound (0 when the engine does not say).
     */
    data class StaleAnchor(val anchorAgePeriods: Long, val boundPeriods: Long) : LightClientStatus

    /** There is no light client on this device, or it failed to start. */
    data object Unavailable : LightClientStatus
}
