package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatch
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [HistoryStarts] in memory, as the port's contract says one behaves; [failSaves] makes every write fail. */
internal class MemoryHistoryStarts(vararg initial: Pair<Address, HistoryStart>) : HistoryStarts {
    val starts = MutableStateFlow(initial.toMap())
    var failSaves = false

    override fun observe(): Flow<HistoryStartList> = starts.map { HistoryStartList.Loaded(it) }

    override suspend fun note(address: Address, atSeconds: Long): Boolean {
        if (failSaves) return false
        starts.value = starts.value + (address to HistoryStart.Noted(atSeconds))
        return true
    }

    override suspend fun fix(address: Address, block: Long): Boolean {
        if (failSaves || starts.value[address] is HistoryStart.Fixed) return false
        starts.value = starts.value + (address to HistoryStart.Fixed(block))
        return true
    }

    override suspend fun forget(address: Address, start: HistoryStart): Boolean {
        if (failSaves) return false
        if (starts.value[address] == start) starts.value = starts.value - address
        return true
    }
}

/**
 * A [LogIndex] that records each change asked of it and [watched] what it took, the union an
 * engine keeps (the lowest block of each entry); [takes] false refuses every change. Its
 * [coverage] and the [answer] to each query ([queries]) are a test's to set.
 */
internal class RecordingLogIndex : LogIndex {
    val asked = mutableListOf<Pair<List<LogWatch>, List<LogWatchKey>>>()
    val watched = mutableMapOf<LogWatchKey, Long>()
    var takes = true

    override suspend fun configure(watch: List<LogWatch>, unwatch: List<LogWatchKey>): Boolean {
        asked += watch to unwatch
        if (!takes) return false
        unwatch.forEach { watched.remove(it) }
        watch.forEach { entry -> watched[entry.key] = minOf(entry.fromBlock, watched[entry.key] ?: Long.MAX_VALUE) }
        return true
    }

    val coverage = MutableStateFlow<LogCoverageReading>(LogCoverageReading.Unavailable)
    val queries = mutableListOf<LogQuery>()
    var answer: (LogQuery) -> LogsReading = { LogsReading.Unavailable(ReadUnavailable.UNPROVEN) }

    override fun observeCoverage(): Flow<LogCoverageReading> = coverage

    override suspend fun logs(query: LogQuery): LogsReading {
        queries += query
        return answer(query)
    }
}
