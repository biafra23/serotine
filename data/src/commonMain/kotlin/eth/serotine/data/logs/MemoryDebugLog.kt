package eth.serotine.data.logs

import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import eth.serotine.domain.repository.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Where an adapter writes what it records about its work: a [DebugLog]'s writing side, which
 * the adapters are handed by the composition root.
 */
interface LogSink {
    /** Whether a record at [level] would be kept: what an adapter asks before it builds a costly one. */
    fun isLoggable(level: LogLevel): Boolean

    /**
     * Records [message] from [source] at [level], or nothing below the log's level. Never throws,
     * never blocks: any thread may call it. [message] must hold no key material.
     */
    fun log(level: LogLevel, source: String, message: String)
}

/**
 * The [DebugLog] of the process, in memory only: the last [capacity] records, the oldest
 * dropped first, gone when the process ends. It starts at [LogLevel.INFO]. [now] dates each
 * record, in unix milliseconds.
 */
@OptIn(ExperimentalTime::class)
class MemoryDebugLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DebugLog, LogSink {

    init {
        require(capacity > 0) { "a log keeps at least one record" }
    }

    /** The records kept and the id the next one gets: one value, so an add is one atomic update. */
    private class Kept(val records: List<LogRecord>, val nextId: Long)

    private val kept = MutableStateFlow(Kept(emptyList(), 1))

    private val level = MutableStateFlow(LogLevel.INFO)

    override fun observeRecords(): Flow<List<LogRecord>> = kept.map { it.records }

    override fun observeLevel(): Flow<LogLevel> = level

    override fun setLevel(level: LogLevel) {
        this.level.value = level
    }

    override fun clear() {
        kept.update { Kept(emptyList(), it.nextId) }
    }

    override fun isLoggable(level: LogLevel): Boolean = level >= this.level.value

    override fun log(level: LogLevel, source: String, message: String) {
        if (!isLoggable(level)) return
        val at = now()
        kept.update { current ->
            val record = LogRecord(current.nextId, at, level, source, message)
            val records = if (current.records.size < capacity) {
                current.records + record
            } else {
                current.records.subList(current.records.size - capacity + 1, current.records.size) + record
            }
            Kept(records, current.nextId + 1)
        }
    }

    private companion object {
        /** Enough for some minutes of a syncing light client's requests at INFO. */
        const val DEFAULT_CAPACITY = 2_000
    }
}
