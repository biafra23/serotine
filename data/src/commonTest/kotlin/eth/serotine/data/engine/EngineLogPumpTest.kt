package eth.serotine.data.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLogPumpTest {

    /** The engine's ring: drained oldest first, newline-joined, empty when nothing waits. */
    private class Ring {
        val lines = ArrayDeque<String>()
        var calls = 0

        fun drain(max: Int): String {
            calls++
            val taken = List(minOf(max, lines.size)) { lines.removeFirst() }
            return taken.joinToString("\n")
        }
    }

    private val ring = Ring()
    private val logged = mutableListOf<String>()
    private val pump = EngineLogPump(ring::drain) { logged += it }

    @Test
    fun marksEveryLineAsTheEngines() {
        ring.lines += listOf(" INFO sync: head 100", " WARN peers: dropped 1.2.3.4") // as the engine indents them

        assertTrue(pump.drainNow())

        assertEquals(listOf("Myotis: INFO sync: head 100", "Myotis: WARN peers: dropped 1.2.3.4"), logged)
    }

    @Test
    fun emptiesARingFullerThanOneBatch() {
        ring.lines += List(EngineLogPump.BATCH * 2 + 3) { "line $it" }

        pump.drainNow()

        assertEquals(EngineLogPump.BATCH * 2 + 3, logged.size)
        assertEquals("Myotis: line 0", logged.first())
        assertEquals("Myotis: line ${EngineLogPump.BATCH * 2 + 2}", logged.last())
        assertEquals(3, ring.calls) // two full batches, then the short one that says it is empty
    }

    @Test
    fun logsNothingWhileTheEngineIsQuiet() {
        assertTrue(pump.drainNow())

        assertEquals(emptyList(), logged)
        assertEquals(1, ring.calls)
    }

    @Test
    fun aDrainThatThrowsEndsThePumpWithoutEscaping() = runTest {
        val failing = EngineLogPump({ error("no engine") }) { logged += it }

        assertFalse(failing.drainNow())
        failing.run(1.seconds) // returns rather than throwing or looping

        assertEquals(emptyList(), logged)
    }

    @Test
    fun drainsOnEveryIntervalUntilCancelled() = runTest {
        val running = launch { pump.run(1.seconds) }
        runCurrent()
        ring.lines += "first"
        advanceTimeBy(1.seconds)
        runCurrent()
        ring.lines += "second"
        advanceTimeBy(1.seconds)
        runCurrent()
        running.cancel()
        ring.lines += "after"
        advanceTimeBy(5.seconds)

        assertEquals(listOf("Myotis: first", "Myotis: second"), logged)
    }
}
