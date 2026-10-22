package eth.serotine.data.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FinalizedTimesTest {

    // A block object as the engine's getBlockByNumber answers it, trimmed: Sepolia's finalized
    // block 11,968,920 of part 0's spike, timestamp 1792664532.
    private val block = """{"number":"0xb6a198","hash":"0x${"ab".repeat(32)}","timestamp":"0x6ad9e3d4"}"""

    @Test
    fun theTimestampIsTheBlocksOwnReadOnceAMove() {
        val times = FinalizedTimes()
        val asked = mutableListOf<String>()
        assertEquals(1_792_664_532L, times.of(11_968_920) { asked += it; block })
        assertEquals(1_792_664_532L, times.of(11_968_920) { asked += it; block })
        assertEquals(listOf("0xb6a198"), asked, "read once for the block")
        times.of(11_968_952) { asked += it; block }
        assertEquals(2, asked.size, "read again once the finalized block moved")
    }

    @Test
    fun noBlockOrNoAnswerIsNoTime() {
        // No retry gap here: each answer below is read.
        val times = FinalizedTimes(retryAfter = 0.seconds)
        assertNull(times.of(0) { block }, "no finalized block yet")
        assertNull(times.of(11_968_920) { null })
        assertNull(times.of(11_968_920) { "null" }, "a verified not found")
        assertNull(times.of(11_968_920) { throw IllegalStateException("the engine failed") })
        assertNull(times.of(11_968_920) { """{"timestamp":17}""" }, "not hex text")
        assertNull(times.of(11_968_920) { """{"error":"no verified answer"}""" })
        assertEquals(1_792_664_532L, times.of(11_968_920) { block }, "an answer missing a moment ago may be had now")
    }

    @Test
    fun aReadThatFailedWaitsItsGapAndNoneIsMadeWhileReadsAreNotVerified() {
        val clock = TestTimeSource()
        val times = FinalizedTimes(retryAfter = 12.seconds, timeSource = clock)
        var reads = 0
        assertNull(times.of(11_968_920, mayRead = false) { reads++; block }, "the light client does not verify now")
        assertEquals(0, reads)

        assertNull(times.of(11_968_920) { reads++; null })
        clock += 11.seconds
        assertNull(times.of(11_968_920) { reads++; block }, "not again within the gap")
        assertEquals(1, reads)
        assertEquals(1_792_664_532L, times.of(11_968_952) { reads++; block }, "a new finalized block is read at once")
        assertEquals(2, reads)

        assertNull(times.of(11_968_984) { reads++; null })
        clock += 12.seconds
        assertEquals(1_792_664_532L, times.of(11_968_984) { reads++; block }, "again after the gap")
        assertEquals(1_792_664_532L, times.of(11_968_984, mayRead = false) { reads++; block }, "known: no read")
        assertEquals(4, reads)
    }
}
