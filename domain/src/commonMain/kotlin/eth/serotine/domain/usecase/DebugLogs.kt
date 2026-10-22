package eth.serotine.domain.usecase

import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.DebugLog
import eth.serotine.domain.repository.DebugScreenSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Whether the Debug tab is on, as Settings and the bottom bar show it. */
class ObserveDebugScreen(private val setting: DebugScreenSetting) {
    operator fun invoke(): Flow<Boolean> = setting.observe()
}

/**
 * Turns the Debug tab on or off; false when that could not be saved. Turning it off also
 * empties the log and sets it back to [LogLevel.INFO], so what a debugging session recorded in
 * detail does not stay in memory behind a tab no longer shown.
 */
class SetDebugScreen(
    private val setting: DebugScreenSetting,
    private val log: DebugLog,
) {
    suspend operator fun invoke(enabled: Boolean): Boolean {
        val saved = setting.set(enabled)
        if (saved && !enabled) {
            log.setLevel(LogLevel.INFO)
            log.clear()
        }
        return saved
    }
}

/**
 * The debug log as the Debug tab shows it: its level, and the records kept at that level and
 * above, newest first. A record made at a lower level before the level was raised is left out.
 */
class ObserveDebugLog(private val log: DebugLog) {
    operator fun invoke(): Flow<DebugLogView> = combine(log.observeLevel(), log.observeRecords()) { level, records ->
        DebugLogView(level, records.filter { it.level >= level }.reversed())
    }
}

/** Sets the level the debug log records at. */
class SetDebugLogLevel(private val log: DebugLog) {
    operator fun invoke(level: LogLevel) = log.setLevel(level)
}

/** Empties the debug log. */
class ClearDebugLog(private val log: DebugLog) {
    operator fun invoke() = log.clear()
}

/** The debug log's [level], and its [records] at that level and above, newest first. */
data class DebugLogView(val level: LogLevel, val records: List<LogRecord>)
