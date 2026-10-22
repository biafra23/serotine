package eth.serotine.data.engine

import eth.serotine.domain.repository.FinalizedBlock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class FinalizedTimesTest {

    // A block object as the engine's getBlockByNumber answers it, trimmed: Sepolia's finalized
    // block 11,968,920 of part 0's spike, timestamp 1792664532.
    private fun header(number: Long, timestamp: Long = 1_792_664_532) =
        """{"number":"0x${number.toString(16)}","hash":"0x${"ab".repeat(32)}",""" +
            """"timestamp":"0x${timestamp.toString(16)}"}"""

    private val clock = TestTimeSource()

    private fun TestScope.times(retryAfter: Duration = 12.seconds) = FinalizedTimes(this, retryAfter, clock)

    /** [block]'s time as [times] has it once a read it starts has run. */
    private fun TestScope.after(
        times: FinalizedTimes,
        block: Long,
        mayRead: Boolean = true,
        read: (Long) -> String?,
    ): FinalizedBlock? {
        times.of(block, mayRead, read)
        runCurrent()
        return times.of(block, mayRead = false, read)
    }

    @Test
    fun theTimestampIsTheBlocksOwnReadOnceAMoveOffThePoll() = runTest {
        val times = times()
        val asked = mutableListOf<Long>()
        assertNull(times.of(11_968_920) { asked += it; header(11_968_920) }, "returns at once: the read runs apart")
        runCurrent()
        assertEquals(FinalizedBlock(11_968_920, 1_792_664_532), times.of(11_968_920) { asked += it; null })
        assertEquals(listOf(11_968_920L), asked, "read once for the block")

        val next = header(11_968_952, timestamp = 1_792_664_916)
        assertEquals(FinalizedBlock(11_968_952, 1_792_664_916), after(times, 11_968_952) { asked += it; next })
        assertEquals(2, asked.size, "read again once the finalized block moved")
    }

    @Test
    fun anotherBlocksHeaderOrNoAnswerIsNoTime() = runTest {
        // No retry gap here: each answer below is read.
        val times = times(retryAfter = Duration.ZERO)
        assertNull(after(times, 0) { header(0) }, "no finalized block yet")
        val block = 11_968_920L
        assertNull(after(times, block) { header(block + 64) }, "the head's header, not the block asked for")
        assertNull(after(times, block) { header(block).replace("\"number\":\"0xb6a198\",", "") }, "no number")
        assertNull(after(times, block) { null })
        assertNull(after(times, block) { "null" }, "a verified not found")
        assertNull(after(times, block) { throw IllegalStateException("the engine failed") })
        assertNull(after(times, block) { """{"number":"0xb6a198","timestamp":17}""" }, "not hex text")
        assertNull(after(times, block) { """{"error":"no verified answer"}""" })
        assertEquals(FinalizedBlock(block, 1_792_664_532), after(times, block) { header(block) }, "had now")
    }

    @Test
    fun theNewestHeaderHadStandsUntilANewerIsHad() = runTest {
        val times = times()
        after(times, 11_968_920) { header(11_968_920) }
        val had = FinalizedBlock(11_968_920, 1_792_664_532)
        assertEquals(had, after(times, 11_968_952) { null }, "the newer header not had: the older stands")
        assertEquals(had, times.of(11_968_900) { header(11_968_900) }, "never back to an older block")
    }

    @Test
    fun aReadThatFailedWaitsItsGapAndNoneIsMadeWhileReadsAreNotVerified() = runTest {
        val times = times()
        var reads = 0
        assertNull(after(times, 11_968_920, mayRead = false) { reads++; header(11_968_920) }, "not verifying now")
        assertEquals(0, reads)

        assertNull(after(times, 11_968_920) { reads++; null })
        clock += 11.seconds
        assertNull(after(times, 11_968_920) { reads++; header(11_968_920) }, "not again within the gap")
        assertEquals(1, reads)
        clock += 1.seconds
        val had = after(times, 11_968_920) { reads++; header(11_968_920) }
        assertEquals(FinalizedBlock(11_968_920, 1_792_664_532), had)
        assertEquals(2, reads)

        assertEquals(1_792_664_916, after(times, 11_968_952) { reads++; header(11_968_952, 1_792_664_916) }?.timestamp)
        assertEquals(3, reads, "a new finalized block is read at once")
    }

    @Test
    fun oneReadRunsAtATime() = runTest {
        val times = times()
        var reads = 0
        times.of(11_968_920) { reads++; header(11_968_920) }
        times.of(11_968_920) { reads++; header(11_968_920) }
        runCurrent()
        assertEquals(1, reads, "a poll while a read runs takes what is had")
    }

    @Test
    fun aRunningSnapshotCarriesItsFinalizedHeaderReadThroughTheChain() = runTest {
        val engine = FakeEngineChain()
        engine.base.blocks[11_968_920] = header(11_968_920)
        val chain = FinalizedHeaders(engine, times())
        fun header() = (chain.snapshot() as EngineSnapshot.Running).finalizedHeader

        engine.base.snapshot = running(phase = EngineSnapshot.Phase.CATCHING_UP, finalized = 11_968_920)
        assertNull(header())
        runCurrent()
        assertNull(header(), "not read while the light client does not verify")

        engine.base.snapshot = running(finalized = 11_968_920)
        assertNull(header(), "the read runs apart from the poll")
        runCurrent()
        assertEquals(FinalizedBlock(11_968_920, 1_792_664_532), header())

        engine.base.snapshot = running(finalized = 11_968_952, paused = true)
        runCurrent()
        assertEquals(FinalizedBlock(11_968_920, 1_792_664_532), header(), "asleep: the one had stands")
        engine.base.snapshot = EngineSnapshot.Down
        assertEquals(EngineSnapshot.Down, chain.snapshot())
    }
}
