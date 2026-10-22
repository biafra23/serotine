package eth.serotine.domain.usecase

import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.LogIndex
import eth.serotine.domain.repository.LogQuery
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.LogsReading
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Proves the end of each CoW order this wallet signed that is still live (docs/swaps.md, *Proving
 * an order's end*), from the account's own entry in the engine's log index, never from CoW's API,
 * and records it ([CowOrders.end]). Run by the hosts while the engine runs, as `FollowAccountLogs`
 * is, so that an order's end is proven whatever screen is open, and again after a restart.
 *
 * The proof reads no past state: a verified `getLogs` of the account's `Mention` entry, at topic 1
 * (the owner), for `Trade` and `OrderInvalidated`, from the block finalized when the order was
 * signed to the newest finalized block. Only logs the order's own settlement contract emitted
 * count, and only those carrying its UID ([GPv2Settlement]). The index answers only a range it
 * covers in full, and the adapter refuses one reaching below the entry's start
 * ([LogsReading.BeforeStart]), so an empty answer is a proven absence.
 *
 * - A `Trade` for the order: filled ([CowOrderEnd.Filled]).
 * - Else its `OrderInvalidated`, in a block at or below the finalized one: never filled, cancelled
 *   ([CowOrderEnd.Unfilled]); nothing settles an order after it, and a settlement earlier in the
 *   same block would be a `Trade` in the range.
 * - Else, once the finalized block's own timestamp is past the order's `validTo`: never filled.
 * - A range below the entry's start can never be proven ([CowOrderEnd.NotProvable]); a range not
 *   covered yet, an entry not followed yet, or no answer right now is tried again.
 */
class ProveCowOrderEnds(
    private val cowOrders: CowOrders,
    private val logIndex: LogIndex,
    private val lightClient: LightClient,
) {
    /**
     * Runs until cancelled: each time the record of orders, the finalized block or the index's
     * coverage changes, proves what it can of every live order.
     */
    suspend operator fun invoke() {
        val finalized = lightClient.observeStatus()
            .map { (it as? LightClientStatus.Synced)?.finalized }
            .distinctUntilChanged()
        combine(cowOrders.observe(), finalized, logIndex.observeCoverage()) { orders, final, _ -> orders to final }
            .collectLatest { (orders, final) ->
                val live = (orders as? CowOrderList.Loaded)?.orders.orEmpty().filter { it.live }
                for (order in live) {
                    endOf(order, final)?.let { cowOrders.end(order.uid, it) }
                }
            }
    }

    /** What the chain proves of [order] with [finalized] the newest finalized block; null: nothing yet. */
    internal suspend fun endOf(order: CowOrderRecord, finalized: FinalizedBlock?): CowOrderEnd? {
        if (finalized == null || finalized.number < order.rangeStart) return null
        val query = LogQuery(
            key = LogWatchKey.Mention(order.account),
            fromBlock = order.rangeStart,
            toBlock = finalized.number,
            topic0s = listOf(GPv2Settlement.TRADE_TOPIC, GPv2Settlement.ORDER_INVALIDATED_TOPIC),
            position = 1,
        )
        val logs = when (val reading = logIndex.logs(query)) {
            is LogsReading.Logs -> reading.logs
            is LogsReading.BeforeStart -> return CowOrderEnd.NotProvable
            is LogsReading.NotCovered, LogsReading.NotWatched, is LogsReading.Unavailable -> return null
        }
        logs.firstNotNullOfOrNull { indexed ->
            val log = indexed.log
            GPv2Settlement.tradeOf(order.settlement, log.address, log.topics, log.data)
                ?.takeIf { it.uid == order.uid }
                ?.let { CowOrderEnd.Filled(it, indexed.blockNumber, indexed.transactionHash) }
        }?.let { return it }
        logs.firstOrNull { indexed ->
            val log = indexed.log
            GPv2Settlement.invalidationOf(order.settlement, log.address, log.topics, log.data) == order.uid
        }?.let { return CowOrderEnd.Unfilled(endBlock = it.blockNumber, cancelled = true) }
        return CowOrderEnd.Unfilled(endBlock = finalized.number, cancelled = false)
            .takeIf { finalized.timestamp > order.order.validTo }
    }
}
