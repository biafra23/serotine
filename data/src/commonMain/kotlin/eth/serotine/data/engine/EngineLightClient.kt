package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Duration

/**
 * [LightClient] over the engine's status snapshot, polled every [pollInterval] by each
 * subscriber, read the way Myotis's own readiness ladder reads it: `SYNCING` is the
 * checkpoint bootstrap, `CATCHING_UP` walks sync-committee periods from the engine's
 * reported start period, and `SYNCED` serves reads only with a fresh verified head and a
 * peer that serves proofs. A device without an engine ([chain] null) and an engine that is
 * down answer [LightClientStatus.Unavailable] once and stop; a poll the engine fails is
 * skipped, not reported as a state.
 */
class EngineLightClient internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : LightClient {

    override fun observeStatus(): Flow<LightClientStatus> {
        val chain = chain ?: return flowOf(LightClientStatus.Unavailable)
        return flow {
            while (true) {
                val snapshot = try {
                    chain.snapshot()
                } catch (e: Throwable) {
                    // A cancellation of this poll ends it; anything else, a CancellationException
                    // the engine's own code throws included, is a failed poll, skipped.
                    currentCoroutineContext().ensureActive()
                    null
                }
                if (snapshot == EngineSnapshot.Down) {
                    emit(LightClientStatus.Unavailable)
                    return@flow
                }
                snapshot?.let { emit(statusOf(it)) }
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun statusOf(snapshot: EngineSnapshot): LightClientStatus = when (snapshot) {
        EngineSnapshot.Booting -> LightClientStatus.Starting
        EngineSnapshot.Down -> LightClientStatus.Unavailable
        is EngineSnapshot.Running -> when {
            snapshot.paused -> LightClientStatus.Sleeping
            snapshot.phase == Phase.STALE_ANCHOR -> LightClientStatus.StaleAnchor(
                anchorAgePeriods = (snapshot.syncTargetPeriod - snapshot.syncCurrentPeriod).coerceAtLeast(0),
                boundPeriods = snapshot.wsBoundPeriods,
            )
            snapshot.phase == Phase.STARTING -> LightClientStatus.Starting
            snapshot.phase == Phase.SYNCING -> LightClientStatus.Bootstrapping(snapshot.huntingLightClientServers)
            snapshot.phase == Phase.CATCHING_UP -> {
                val start = snapshot.syncStartPeriod
                val known = start >= 0 && snapshot.syncTargetPeriod > start
                LightClientStatus.CatchingUp(
                    done = if (known) (snapshot.syncCurrentPeriod - start).coerceAtLeast(0) else 0,
                    total = if (known) snapshot.syncTargetPeriod - start else 0,
                )
            }
            snapshot.headAgeMs > EngineSnapshot.FRESH_HEAD_MS -> LightClientStatus.WaitingForHead(
                if (snapshot.headAgeMs == Long.MAX_VALUE) null else snapshot.headAgeMs / 1_000,
            )
            snapshot.proofPeers == 0 ->
                LightClientStatus.WaitingForProofPeers(snapshot.headBlock, snapshot.headTimestamp)
            else -> LightClientStatus.Synced(snapshot.headBlock, snapshot.headTimestamp)
        }
    }
}
