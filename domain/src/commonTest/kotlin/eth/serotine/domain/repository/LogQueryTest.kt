package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** What [LogQuery] and [LogWatch] refuse at construction. */
class LogQueryTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    @Test
    fun aTopicPositionIsAMentionsAndOnlyAMentions() {
        LogQuery(LogWatchKey.Mention(alice), 1, 2, position = 3)
        LogQuery(LogWatchKey.Emitter(alice), 1, 2)

        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Mention(alice), 1, 2) }
        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Emitter(alice), 1, 2, position = 1) }
        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Mention(alice), 1, 2, position = 0) }
        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Mention(alice), 1, 2, position = 4) }
    }

    @Test
    fun aRangeRunsForwardAndATopicIsOneWordInLowerCase() {
        LogQuery(LogWatchKey.Emitter(alice), 5, 5, listOf(transfer))

        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Emitter(alice), 6, 5) }
        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Emitter(alice), -1, 5) }
        val upperCase = listOf(transfer.uppercase())
        assertFailsWith<IllegalArgumentException> { LogQuery(LogWatchKey.Emitter(alice), 1, 5, upperCase) }
        assertFailsWith<IllegalArgumentException> { LogWatch(LogWatchKey.Emitter(alice), -1) }
    }
}
