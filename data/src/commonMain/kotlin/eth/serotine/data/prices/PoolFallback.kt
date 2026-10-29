package eth.serotine.data.prices

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import kotlinx.coroutines.flow.Flow

/**
 * [PriceFeeds] that asks [feeds] for every price id they quote and [pools] only for one they do
 * not (CLAUDE.md, *Data sources*: owner ruling, 2026-10-29, #205). A pinned feed always wins,
 * whatever it answers: a stale feed is shown as stale, never replaced by a pool's average, so
 * the choice is made by the tables alone and never by a reading.
 */
class FeedsThenPools(private val feeds: PriceFeeds, private val pools: PriceFeeds) : PriceFeeds {

    override fun quotes(network: Network): Map<PriceId, PriceId> = pools.quotes(network) + feeds.quotes(network)

    override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> =
        if (priceId in feeds.quotes(network) || priceId !in pools.quotes(network)) {
            feeds.observe(network, priceId)
        } else {
            pools.observe(network, priceId)
        }
}

/**
 * [PriceHistory] read from the same source [FeedsThenPools] reads the price now from: [feeds]'
 * history for a price id [feedTable] quotes, [pools]' for one only [poolTable] does.
 */
class FeedHistoryThenPools(
    private val feeds: PriceHistory,
    private val feedTable: PriceFeeds,
    private val pools: PriceHistory,
    private val poolTable: PriceFeeds,
) : PriceHistory {

    override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading =
        if (priceId in feedTable.quotes(network) || priceId !in poolTable.quotes(network)) {
            feeds.before(network, priceId, secondsBefore)
        } else {
            pools.before(network, priceId, secondsBefore)
        }
}
