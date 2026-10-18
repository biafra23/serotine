package eth.serotine.data.engine

import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.pow
import kotlin.time.Duration

/**
 * [PriceFeeds] over a verified light client: each [PinnedFeed]'s `decimals()` and
 * `latestRoundData()` as verified calls ([VerifiedChain.call]), through [ProvenReads], at
 * every verified head. `decimals()` is read every time, since the proxy's aggregator can be
 * replaced; one that differs from the pin, a non-positive answer, or what is no round, is no
 * price. A round is stale once it is older than the feed's heartbeat and [STALE_GRACE_SECONDS]
 * (a heartbeat update lands a little after the heartbeat, not at it), measured against the
 * time of the verified head the read was made at, never the device's clock; a head whose time
 * is not known yet gives no reading (unproven) rather than a guess. A round dated more than
 * [FUTURE_SKEW_SECONDS] after that head is what no feed answers.
 */
class VerifiedPriceFeeds internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    feeds: List<PinnedFeed>,
) : PriceFeeds {

    private val pinned = feeds.associateBy { it.priceId }

    override val quotes: Map<PriceId, PriceId> = feeds.associate { it.priceId to it.quote }

    private val reads = ProvenReads<PriceId, FeedReading>(chain, dispatcher, pollInterval) { verified, id ->
        val feed = pinned.getValue(id)
        // The head's time before the calls: the calls block, and the head may move meanwhile.
        val headTime = (verified.snapshot() as? EngineSnapshot.Running)?.headTimestamp
        val decimals = verified.call(feed.address, Abi.DECIMALS) ?: return@ProvenReads null
        if (decimals == CallOutcome.Refused) return@ProvenReads REFUSED
        val round = verified.call(feed.address, Abi.LATEST_ROUND_DATA) ?: return@ProvenReads null
        if (round == CallOutcome.Refused) return@ProvenReads REFUSED
        // A refusal is said whatever the head's time; an answer cannot be dated without it.
        if (headTime == null) return@ProvenReads null
        val block = (round as? CallOutcome.Returned)?.blockNumber
        EngineReading.Proven(reading(feed, decimals, round, headTime), block ?: UNKNOWN_BLOCK)
    }

    override fun observe(priceId: PriceId): Flow<FeedReading> {
        if (priceId !in pinned) return flowOf(FeedReading.NoPrice(NoPriceReason.NO_FEED))
        return reads.observe(priceId).map { reading ->
            when (reading) {
                EngineReading.Syncing -> FeedReading.Syncing
                is EngineReading.Proven -> reading.value
                is EngineReading.Unavailable -> FeedReading.Unavailable(reading.reason)
            }
        }.distinctUntilChanged()
    }

    private fun reading(feed: PinnedFeed, decimals: CallOutcome, round: CallOutcome, headTime: Long): FeedReading {
        val places = (decimals as? CallOutcome.Returned)?.let { Abi.uint256(it.hex) }?.toIntOrNull()
            ?: return FeedReading.NoPrice(NoPriceReason.NOT_A_FEED)
        if (places != feed.decimals) return FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED)
        val data = (round as? CallOutcome.Returned)?.let { Abi.round(it.hex) }
            // A few slots past the head the time was taken at, a call can run at a later one.
            ?.takeIf { it.updatedAt > 0 && it.updatedAt <= headTime + FUTURE_SKEW_SECONDS }
            ?: return FeedReading.NoPrice(NoPriceReason.NOT_A_FEED)
        val answer = data.answer?.takeIf { it != "0" } ?: return FeedReading.NoPrice(NoPriceReason.NOT_POSITIVE)
        // A round dated after the head's time (a call run at a later head) is no older than it.
        val age = (headTime - data.updatedAt).coerceAtLeast(0)
        return FeedReading.Verified(
            price = answer.toDouble() / 10.0.pow(places),
            updatedAt = data.updatedAt,
            ageSeconds = age,
            stale = age > feed.heartbeatSeconds + STALE_GRACE_SECONDS,
        )
    }

    internal companion object {
        /** How long after its heartbeat a round may still land: its own consensus and inclusion. */
        const val STALE_GRACE_SECONDS = 600L

        /** How far after the head's time a round may be dated: a call answered a few heads later. */
        const val FUTURE_SKEW_SECONDS = 120L

        private val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)

        /** What [ProvenReads] is given for a call whose block the engine did not name. */
        private const val UNKNOWN_BLOCK = -1L
    }
}
