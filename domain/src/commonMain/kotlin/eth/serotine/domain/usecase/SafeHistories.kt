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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * A Safe's own history on chain (issue #85): its events ([SafeEvents]) as the engine's log index
 * holds them, from where its history starts ([HistoryStarts], kept by `FollowAccountLogs`) up to
 * the top of what the index covers of it, and never below either end of that span.
 *
 * Read again each time the covered span moves (about every slot while the index follows the
 * head), only for the blocks it did not cover before: the events already read stay, unless the
 * span shrank (a reorganisation the index rewound), which reads it all again. A read that fails,
 * or a coverage that cannot be had for a moment, keeps the events read before rather than
 * dropping them: they are verified, and what they cover is said with them.
 */
class ObserveSafeHistory(private val index: LogIndex, private val starts: HistoryStarts) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(safe: Address): Flow<SafeHistory> = flow {
        var last: SafeHistory.Read? = null
        // Unavailable until the first coverage poll answers, so that no one waits on the engine.
        val coverage = index.observeCoverage().onStart { emit(LogCoverageReading.Unavailable) }
        emitAll(
            combine(starts.observe(), coverage) { held, covered -> spanOf(safe, held, covered) }
                .distinctUntilChanged()
                .transformLatest { span ->
                    val shown = when (span) {
                        is Span.Ready -> read(safe, span, last.takeIf { it?.start == span.start })
                        is Span.Other -> when (val history = span.history) {
                            SafeHistory.NotFollowed -> history
                            SafeHistory.Unavailable -> last ?: history
                            is SafeHistory.Checking -> last.takeIf { it?.start == history.start } ?: history
                            is SafeHistory.Read -> history
                        }
                    }
                    last = shown as? SafeHistory.Read ?: last.takeIf { shown !== SafeHistory.NotFollowed }
                    emit(shown)
                },
        )
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

    /**
     * The events of [span]: those of [before] (read for the same start) kept, and only the blocks
     * it did not cover read; all of [span] when there is no [before] or the span shrank. A read
     * that does not answer keeps [before], or says why there is none.
     */
    private suspend fun read(safe: Address, span: Span.Ready, before: SafeHistory.Read?): SafeHistory {
        val checked = span.checked
        val grown = before != null && checked.first <= before.checked.first && checked.last >= before.checked.last
        val ranges = if (before == null || !grown) {
            listOf(checked)
        } else {
            listOf(checked.first until before.checked.first, before.checked.last + 1..checked.last)
                .filterNot { it.isEmpty() }
        }
        val kept = if (before != null && grown) before.events else emptyList()
        val read = ranges.map { range ->
            val query = LogQuery(LogWatchKey.Emitter(safe), range.first, range.last, SafeEvents.TOPICS)
            when (val answer = index.logs(query)) {
                is LogsReading.Logs -> answer.logs.mapNotNull { log ->
                    SafeEvents.decode(log.log.topics, log.log.data)?.let {
                        SafeChainEvent(it, log.blockNumber, log.transactionHash, log.logIndex)
                    }
                }
                // The span moved between the coverage and the query: the next coverage reads it again.
                is LogsReading.NotCovered, is LogsReading.BeforeStart, LogsReading.NotWatched ->
                    return before ?: SafeHistory.Checking(span.start)
                is LogsReading.Unavailable -> return before ?: SafeHistory.Unavailable
            }
        }
        val events = (read.flatten() + kept).sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
        return SafeHistory.Read(events, span.start, checked)
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
