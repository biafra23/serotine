package eth.serotine.domain.repository

import eth.serotine.domain.model.PriceId
import kotlinx.coroutines.flow.Flow

/**
 * The port for prices (CLAUDE.md, *Data sources*: Chainlink aggregators read through the
 * light client): each pinned feed's answer as a verified call. :data implements it over the
 * Myotis engine with a feed table per network. The light client proves what the aggregator
 * says, not that the oracle is right, so a price is shown and never signed.
 */
interface PriceFeeds {
    /**
     * Each price id with a feed pinned on the network the app works on, and the price id its
     * feed quotes in ([PriceId.USD], or another one, as an X/ETH feed does). Fixed while the
     * app runs.
     */
    val quotes: Map<PriceId, PriceId>

    /**
     * What the feed of [priceId] answers, re-emitted when the verified head moves; equal
     * readings are not repeated. [FeedReading.NoPrice] with [NoPriceReason.NO_FEED], once, for
     * a price id with no feed in [quotes]. Never fails.
     */
    fun observe(priceId: PriceId): Flow<FeedReading>
}

/** One answer of [PriceFeeds.observe]. */
sealed interface FeedReading {

    /** The light client has no verified head yet. */
    data object Syncing : FeedReading

    /**
     * One unit of the priced asset costs [price] in the feed's quote, as of the feed's last
     * update at [updatedAt] (unix seconds): [ageSeconds] before the verified head, by the
     * head's own time, never the device's clock; [stale] once that is longer than the feed's
     * heartbeat.
     */
    data class Verified(
        val price: Double,
        val updatedAt: Long,
        val ageSeconds: Long,
        val stale: Boolean,
    ) : FeedReading

    /** The feed answered, verified, with something that is no price, for [reason]. */
    data class NoPrice(val reason: NoPriceReason) : FeedReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : FeedReading
}

/** Why a verified feed answer is no price. */
enum class NoPriceReason {
    /** No feed is pinned for it on the network. */
    NO_FEED,

    /** The feed's decimals are not the pinned ones: its aggregator was replaced. */
    DECIMALS_CHANGED,

    /** The feed answered zero or less. */
    NOT_POSITIVE,

    /** What answered is no feed: a revert, or no round data. */
    NOT_A_FEED,
}
