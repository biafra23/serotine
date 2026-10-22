package eth.serotine.data.logs

import eth.serotine.domain.model.LogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryDebugLogTest {

    private var time = 1_000L
    private val log = MemoryDebugLog(capacity = 3, now = { time++ })

    @Test
    fun itRecordsAtInfoAndAboveUntilItsLevelIsLowered() = runTest {
        assertEquals(LogLevel.INFO, log.observeLevel().first())
        assertFalse(log.isLoggable(LogLevel.DEBUG))

        log.log(LogLevel.DEBUG, "Myotis", "not kept")
        log.log(LogLevel.INFO, "Myotis", "kept")
        log.setLevel(LogLevel.DEBUG)
        log.log(LogLevel.DEBUG, "Myotis", "kept too")

        val records = log.observeRecords().first()
        assertEquals(listOf("kept", "kept too"), records.map { it.message })
        assertEquals(listOf(LogLevel.INFO, LogLevel.DEBUG), records.map { it.level })
        assertEquals(listOf(1_000L, 1_001L), records.map { it.epochMillis })
        assertTrue(records.all { it.source == "Myotis" })
    }

    @Test
    fun itKeepsTheLastRecordsOnlyAndNumbersThemOnAcrossAClear() = runTest {
        (1..5).forEach { log.log(LogLevel.INFO, "Myotis", "request $it") }

        val kept = log.observeRecords().first()
        assertEquals(listOf("request 3", "request 4", "request 5"), kept.map { it.message })
        assertEquals(listOf(3L, 4L, 5L), kept.map { it.id })

        log.clear()
        assertTrue(log.observeRecords().first().isEmpty())

        log.log(LogLevel.INFO, "Myotis", "after")
        assertEquals(listOf(6L), log.observeRecords().first().map { it.id })
    }
}
