package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SafeEvents
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * A Safe's own history on chain (issue #85): its events ([SafeEvents]) as the engine's log index
 * holds them, from where its history starts ([HistoryStarts], kept by `FollowAccountLogs`) up to
 * the top of what the index covers of it, and never below either end of that span. Read again
 * each time the covered span moves (about every slot while the index follows the head).
 */
class ObserveSafeHistory(private val index: LogIndex, private val starts: HistoryStarts) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(safe: Address): Flow<SafeHistory> =
        combine(starts.observe(), index.observeCoverage()) { held, coverage -> spanOf(safe, held, coverage) }
            .distinctUntilChanged()
            .transformLatest { span ->
                when (span) {
                    is Span.Ready -> emit(read(safe, span))
                    is Span.Other -> emit(span.history)
                }
            }

    private fun spanOf(safe: Address, held: HistoryStartList, coverage: LogCoverageReading): Span {
        val start = when (held) {
            HistoryStartList.Unreadable -> return Span.Other(SafeHistory.Unavailable)
            is HistoryStartList.Loaded -> (held.starts[safe] as? HistoryStart.Fixed)?.block
        } ?: return Span.Other(SafeHistory.NotFollowed)
        val entries = (coverage as? LogCoverageReading.Read)?.entries ?: return Span.Other(SafeHistory.Unavailable)
        val covered = entries[LogWatchKey.Emitter(safe)]?.covered ?: return Span.Other(SafeHistory.Checking(start))
        val from = maxOf(covered.first, start)
        if (from > covered.last) return Span.Other(SafeHistory.Checking(start))
        return Span.Ready(start, from..covered.last)
    }

    private suspend fun read(safe: Address, span: Span.Ready): SafeHistory {
        val query = LogQuery(LogWatchKey.Emitter(safe), span.checked.first, span.checked.last, SafeEvents.TOPICS)
        return when (val answer = index.logs(query)) {
            is LogsReading.Logs -> SafeHistory.Read(
                events = answer.logs.mapNotNull { log ->
                    SafeEvents.decode(log.log.topics, log.log.data)?.let {
                        SafeChainEvent(it, log.blockNumber, log.transactionHash, log.logIndex)
                    }
                },
                start = span.start,
                checked = span.checked,
            )
            // The span moved between the coverage and the query: the next coverage reads it again.
            is LogsReading.NotCovered, is LogsReading.BeforeStart, LogsReading.NotWatched ->
                SafeHistory.Checking(span.start)
            is LogsReading.Unavailable -> SafeHistory.Unavailable
        }
    }

    /** What is to be read: a [Ready] span, or an [Other] answer that needs no read. */
    private sealed interface Span {
        data class Ready(val start: Long, val checked: LongRange) : Span
        data class Other(val history: SafeHistory) : Span
    }
}

/** What [ObserveSafeHistory] emits. */
sealed interface SafeHistory {

    /** No block its history starts at is fixed yet: once the light client is synced, it is. */
    data object NotFollowed : SafeHistory

    /** It is followed from block [start], and the index has no part of that span to read yet. */
    data class Checking(val start: Long) : SafeHistory

    /**
     * Its [events] in blocks [checked], in block order: every one of them in that span. It is
     * followed from block [start]; the blocks from [start] to below [checked] are still being walked.
     */
    data class Read(val events: List<SafeChainEvent>, val start: Long, val checked: LongRange) : SafeHistory {
        /** Whether every block from its start on is checked. */
        val complete: Boolean
            get() = checked.first <= start
    }

    /** It cannot be read right now (the engine asleep or not running, the starts unreadable). */
    data object Unavailable : SafeHistory
}

/** A Safe's [event], in block [blockNumber], logged by transaction [transactionHash] at [logIndex] in the block. */
data class SafeChainEvent(
    val event: SafeEvent,
    val blockNumber: Long,
    val transactionHash: String,
    val logIndex: Int,
)
