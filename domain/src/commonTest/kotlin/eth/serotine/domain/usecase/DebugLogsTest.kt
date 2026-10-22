package eth.serotine.domain.usecase

import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Debug tab's switch and its log, over fake ports. */
class DebugLogsTest {

    private val log = MemoryLog()
    private val setting = MemorySetting()

    @Test
    fun theLogShowsTheRecordsAtItsLevelAndAboveNewestFirst() = runTest {
        log.records.value = listOf(record(1, LogLevel.INFO), record(2, LogLevel.DEBUG), record(3, LogLevel.INFO))

        val info = ObserveDebugLog(log)().first()
        assertEquals(LogLevel.INFO, info.level)
        assertEquals(listOf(3L, 1L), info.records.map { it.id })

        SetDebugLogLevel(log)(LogLevel.DEBUG)
        assertEquals(listOf(3L, 2L, 1L), ObserveDebugLog(log)().first().records.map { it.id })
    }

    @Test
    fun clearingEmptiesTheLog() = runTest {
        log.records.value = listOf(record(1, LogLevel.INFO))

        ClearDebugLog(log)()

        assertTrue(ObserveDebugLog(log)().first().records.isEmpty())
    }

    @Test
    fun turningTheTabOffEmptiesTheLogAndSetsItBackToInfo() = runTest {
        SetDebugScreen(setting, log)(true)
        log.level.value = LogLevel.DEBUG
        log.records.value = listOf(record(1, LogLevel.DEBUG))
        assertTrue(ObserveDebugScreen(setting)().first())

        assertTrue(SetDebugScreen(setting, log)(false))

        assertFalse(ObserveDebugScreen(setting)().first())
        assertEquals(LogLevel.INFO, log.level.value)
        assertTrue(log.records.value.isEmpty())
    }

    @Test
    fun aSwitchThatCannotBeSavedLeavesTheLogAsItIs() = runTest {
        setting.enabled.value = true
        setting.failSaves = true
        log.level.value = LogLevel.DEBUG
        log.records.value = listOf(record(1, LogLevel.DEBUG))

        assertFalse(SetDebugScreen(setting, log)(false))

        assertTrue(setting.enabled.value)
        assertEquals(LogLevel.DEBUG, log.level.value)
        assertEquals(1, log.records.value.size)
    }

    private fun record(id: Long, level: LogLevel) = LogRecord(id, 1_000 * id, level, "Myotis", "request $id")

    private class MemoryLog : DebugLog {
        val records = MutableStateFlow<List<LogRecord>>(emptyList())
        val level = MutableStateFlow(LogLevel.INFO)

        override fun observeRecords(): Flow<List<LogRecord>> = records
        override fun observeLevel(): Flow<LogLevel> = level
        override fun setLevel(level: LogLevel) {
            this.level.value = level
        }
        override fun clear() {
            records.value = emptyList()
        }
    }

    private class MemorySetting : DebugScreenSetting {
        val enabled = MutableStateFlow(false)
        var failSaves = false

        override fun observe(): Flow<Boolean> = enabled
        override suspend fun set(enabled: Boolean): Boolean {
            if (failSaves) return false
            this.enabled.value = enabled
            return true
        }
    }
}
