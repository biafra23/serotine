package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SafeEvents
import eth.serotine.domain.repository.HistoryStarts
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import kotlinx.coroutines.flow.Flow

/**
 * A Safe's own history on chain (issue #85): its events ([SafeEvents]) as the engine's log index
 * holds them for its [LogWatchKey.Emitter] entry, read as [IndexedReader] reads an entry.
 */
class ObserveSafeHistory(private val index: LogIndex, private val starts: HistoryStarts) {

    operator fun invoke(safe: Address): Flow<IndexedHistory<SafeChainEvent>> = IndexedReader(
        index,
        starts,
        LogWatchKey.Emitter(safe),
        queries = { range -> listOf(LogQuery(LogWatchKey.Emitter(safe), range.first, range.last, SafeEvents.TOPICS)) },
        decode = { log ->
            SafeEvents.decode(log.log.topics, log.log.data)?.let {
                SafeChainEvent(it, log.blockNumber, log.transactionHash, log.logIndex)
            }
        },
    ).observe()
}

/** A Safe's [event], in block [blockNumber], logged by transaction [transactionHash] at [logIndex] in the block. */
data class SafeChainEvent(
    val event: SafeEvent,
    override val blockNumber: Long,
    override val transactionHash: String,
    override val logIndex: Int,
) : IndexedEvent
