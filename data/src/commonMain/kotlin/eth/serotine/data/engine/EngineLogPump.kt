package eth.serotine.data.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * Carries the engine's own log into a host's debug log. The Rust engine prints nothing: it
 * writes its tracing lines into a ring of its own (4096 lines, the oldest dropped when full),
 * which a host has to drain, so without this a running engine's account of its sync (peers
 * found and dropped, heads followed, proofs refused) is never seen. [drain] takes up to its
 * argument's lines out of that ring, oldest first and newline-joined, empty when none wait;
 * each line goes to [sink] behind [PREFIX], its indent dropped. Engine state only: no key
 * material is ever in the engine, so none can reach the log this way.
 */
internal class EngineLogPump(
    private val drain: (Int) -> String,
    private val sink: (String) -> Unit,
) {

    /**
     * Drains what waits now, batch by batch, until the ring is empty. A drain that throws ends
     * the pump's work quietly: the log is for watching the engine and must never stop it.
     * Returns false then, true otherwise.
     */
    fun drainNow(): Boolean {
        while (true) {
            val batch = try {
                drain(BATCH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return false
            }
            if (batch.isEmpty()) return true
            val lines = batch.split('\n')
            lines.forEach { sink(PREFIX + it.trimStart()) } // the engine indents its level
            if (lines.size < BATCH) return true
        }
    }

    /** Drains every [interval] until cancelled, or until a drain throws. */
    suspend fun run(interval: Duration) {
        while (drainNow()) delay(interval)
    }

    companion object {
        /** What each engine line is marked with, beside the adapters' own `Serotine:` lines. */
        const val PREFIX = "Myotis: "

        /** Lines taken per drain call; the ring is emptied in as many calls as it takes. */
        const val BATCH = 256
    }
}
