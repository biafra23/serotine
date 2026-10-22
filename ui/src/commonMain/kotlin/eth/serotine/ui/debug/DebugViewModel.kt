package eth.serotine.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.usecase.ClearDebugLog
import eth.serotine.domain.usecase.ObserveDebugLog
import eth.serotine.domain.usecase.SetDebugLogLevel
import eth.serotine.ui.platform.formatTimeOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Debug tab's ViewModel: the debug log at the level chosen, filtered by the text typed, and
 * the intents that change the level, the filter and empty the log.
 */
class DebugViewModel(
    observeDebugLog: ObserveDebugLog,
    private val setDebugLogLevel: SetDebugLogLevel,
    private val clearDebugLog: ClearDebugLog,
    /** A record's time, unix milliseconds, as the time of day it is shown with. */
    private val formatTime: (Long) -> String = ::formatTimeOfDay,
) : ViewModel() {

    private val filter = MutableStateFlow("")

    val uiState: StateFlow<DebugUiState> =
        combine(observeDebugLog(), filter) { log, filter ->
            val lines = log.records.map { record ->
                val text = "${record.source} · ${record.message}"
                DebugLogLine(record.id, formatTime(record.epochMillis), record.level, text)
            }
            val needle = filter.trim()
            DebugUiState(
                level = log.level,
                filter = filter,
                lines = if (needle.isEmpty()) lines else lines.filter { it.text.contains(needle, ignoreCase = true) },
                recorded = lines.size,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugUiState())

    /** Records at [level] and above from now on, and shows those. */
    fun setLevel(level: LogLevel) = setDebugLogLevel(level)

    /** Shows only the lines that contain [text], in any case; blank shows them all. */
    fun setFilter(text: String) {
        filter.value = text
    }

    /** Empties the log. */
    fun clear() = clearDebugLog()
}
