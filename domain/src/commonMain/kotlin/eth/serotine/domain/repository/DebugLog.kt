package eth.serotine.domain.repository

import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import kotlinx.coroutines.flow.Flow

/**
 * The port for the app's debug log, which the Debug tab shows: what the adapters record about
 * their work, such as each request the light client's engine is asked. :data keeps it in memory
 * for the life of the process, the most recent records only: it is never written to a file and
 * never leaves the device.
 */
interface DebugLog {
    /** The records kept, oldest first, re-emitted on each one added and on [clear]. Never throws. */
    fun observeRecords(): Flow<List<LogRecord>>

    /**
     * The level records are made at, re-emitted on each change: a record below it is not made at
     * all, so lowering it shows more from then on, never what went unrecorded before. Never throws.
     */
    fun observeLevel(): Flow<LogLevel>

    /** Records at [level] and above from now on. */
    fun setLevel(level: LogLevel)

    /** Drops every record kept. */
    fun clear()
}

/**
 * The port for whether the user turned the Debug tab on in Settings. :data keeps it in a file,
 * so it survives a restart.
 */
interface DebugScreenSetting {
    /** Whether the tab is on, re-emitted on each change; off while the choice cannot be read. Never throws. */
    fun observe(): Flow<Boolean>

    /** Turns the tab on or off; false when that could not be saved, and nothing changed. Never throws. */
    suspend fun set(enabled: Boolean): Boolean
}
