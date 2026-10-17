package eth.serotine.data.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

/**
 * This chain's snapshot every [interval], for one subscriber: a poll the engine fails is
 * skipped, not emitted, and [EngineSnapshot.Down], which is final, is emitted once and ends
 * the stream.
 */
internal fun VerifiedChain.snapshots(interval: Duration): Flow<EngineSnapshot> = flow {
    while (true) {
        val snapshot = try {
            snapshot()
        } catch (e: Throwable) {
            // A cancellation of this poll ends it; anything else, a CancellationException
            // the engine's own code throws included, is a failed poll, skipped.
            currentCoroutineContext().ensureActive()
            null
        }
        snapshot?.let { emit(it) }
        if (snapshot == EngineSnapshot.Down) return@flow
        delay(interval)
    }
}
