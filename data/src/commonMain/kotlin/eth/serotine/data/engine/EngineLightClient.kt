package eth.serotine.data.engine

import eth.serotine.data.engine.BeaconSnapshot.Phase
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Duration

/**
 * [LightClient] over the engine's beacon status: each subscriber polls it every
 * [pollInterval]. Sync progress counts periods from the first one this subscriber saw after
 * the bootstrap, since the engine reports where it is and where it is going, not where it
 * began. A device without an engine ([chain] null) answers [LightClientStatus.Unavailable]
 * once; a poll the engine fails is skipped, not reported as a state.
 */
class EngineLightClient internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : LightClient {

    override fun observeStatus(): Flow<LightClientStatus> {
        val chain = chain ?: return flowOf(LightClientStatus.Unavailable)
        return flow {
            var startPeriod: Long? = null
            while (true) {
                val snapshot = try {
                    chain.beacon()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (snapshot is BeaconSnapshot.Running && snapshot.bootstrapped && snapshot.currentPeriod > 0) {
                    startPeriod = startPeriod ?: snapshot.currentPeriod
                }
                snapshot?.let { emit(statusOf(it, startPeriod)) }
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun statusOf(snapshot: BeaconSnapshot, startPeriod: Long?): LightClientStatus = when (snapshot) {
        BeaconSnapshot.Booting -> LightClientStatus.Starting
        BeaconSnapshot.Down -> LightClientStatus.Unavailable
        is BeaconSnapshot.Running -> when {
            snapshot.phase == Phase.STALE_ANCHOR -> LightClientStatus.StaleAnchor(
                anchorAgePeriods = snapshot.targetPeriod - snapshot.currentPeriod,
                boundPeriods = snapshot.wsBoundPeriods,
            )
            snapshot.phase == Phase.STARTING || !snapshot.bootstrapped ->
                LightClientStatus.Bootstrapping(snapshot.servingPeers)
            snapshot.phase == Phase.SYNCING -> {
                val start = startPeriod ?: snapshot.currentPeriod
                LightClientStatus.Syncing(
                    done = (snapshot.currentPeriod - start).coerceAtLeast(0),
                    total = (snapshot.targetPeriod - start).coerceAtLeast(0),
                    peers = snapshot.servingPeers,
                )
            }
            snapshot.phase == Phase.CATCHING_UP || snapshot.headBlock == null ->
                LightClientStatus.CatchingUp(snapshot.knownStateRoots, snapshot.fillThreshold)
            else -> LightClientStatus.Synced(snapshot.headBlock)
        }
    }
}
