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
    fun aFailedStartReportsTheRingsLastLinesAndDrainsTheRest() {
        ring.lines += List(EngineLogPump.BATCH * 20 + 5) { "line $it" } // more than any one drain gives

        val reported = EngineLogPump.lastLines(ring::drain, 3)

        val last = EngineLogPump.BATCH * 20 + 4
        assertEquals("line ${last - 2}\nline ${last - 1}\nline $last", reported)
        assertTrue(ring.lines.isEmpty())
    }

    @Test
    fun aFailedStartKeepsTheLinesTakenBeforeADrainThrew() {
        var calls = 0
        val fullBatch = List(EngineLogPump.BATCH) { "line $it" }.joinToString("\n")
        val drain = { _: Int -> if (calls++ == 0) fullBatch else error("gone") }

        assertEquals("line ${EngineLogPump.BATCH - 1}", EngineLogPump.lastLines(drain, 1))
    }

    @Test
    fun aDrainThatThrowsEndsThePumpWithoutEscapingAndSaysSo() = runTest {
        val failing = EngineLogPump({ error("no engine") }) { logged += it }

        assertFalse(failing.drainNow())
        failing.run(1.seconds) // returns rather than throwing or looping

        assertEquals(2, logged.size)
        assertTrue(logged.all { it.startsWith("Myotis: the engine's log can no longer be drained") })
    }

    @Test
    fun aSinkThatThrowsEndsThePumpWithoutEscaping() = runTest {
        val failing = EngineLogPump({ "a line" }) { error("sink gone") } // a line always waits

        assertFalse(failing.drainNow())
        failing.run(1.seconds)
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
