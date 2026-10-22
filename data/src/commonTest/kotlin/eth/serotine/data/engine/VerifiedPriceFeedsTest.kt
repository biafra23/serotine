package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.prices.PinnedFeed
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedPriceFeedsTest {

    private val eth = PinnedFeed(PriceId("ETH"), Address.of("0x" + "69".repeat(20)), PriceId.USD, 8, 3_600)
    private val chain = FakeChain().apply { snapshot = running(head = 9, headTimestamp = 10_000) }

    private fun TestScope.feeds() =
        VerifiedPriceFeeds(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler), 4.seconds, listOf(eth))

    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    /** `latestRoundData()`'s five words for [answer] updated at [updatedAt]. */
    private fun round(answer: String, updatedAt: Long) = word(7) + answer + word(0) + word(updatedAt) + word(7)

    private fun answer(decimals: CallOutcome, round: CallOutcome) {
        chain.calls[eth.address to Abi.DECIMALS.toHex()] = decimals
        chain.calls[eth.address to Abi.LATEST_ROUND_DATA.toHex()] = round
    }

    @Test
    fun aFreshRoundIsAPriceDatedByTheHeadsOwnTime() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(242_930_491_033), 9_400), 9))

        assertEquals(
            FeedReading.Verified(2_429.30491033, updatedAt = 9_400, ageSeconds = 600, stale = false),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )
    }

    @Test
    fun aRoundOlderThanTheHeartbeatAndItsGraceIsStale() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 5_000), 9))

        val reading = feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        assertEquals(5_000, reading.ageSeconds)
        assertEquals(true, reading.stale)
    }

    @Test
    fun theSameRoundTurnsStaleAsTheVerifiedHeadMovesOn() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(100_000_000), 9_400), 9))
        val feeds = feeds()
        suspend fun reading() = feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing } as FeedReading.Verified
        assertEquals(false, reading().stale)

        // Past the heartbeat, but within the grace a heartbeat update takes to land: not stale.
        chain.snapshot = running(head = 310, headTimestamp = 13_100)
        val late = reading()
        assertEquals(3_700, late.ageSeconds)
        assertEquals(false, late.stale)

        // Past the grace too, and still no new round: stale.
        chain.snapshot = running(head = 400, headTimestamp = 13_100 + VerifiedPriceFeeds.STALE_GRACE_SECONDS)
        assertEquals(true, reading().stale)
    }

    @Test
    fun aRoundDatedWellAfterTheHeadIsNoFeedsAnswer() = runTest {
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(1), 10_000 + 3_600), 9))

        assertEquals(
            FeedReading.NoPrice(NoPriceReason.NOT_A_FEED),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )
    }

    @Test
    fun whatIsNoPriceSaysWhy() = runTest {
        val feeds = feeds()
        suspend fun reading() = feeds.observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing }

        answer(CallOutcome.Returned(word(18), 9), CallOutcome.Returned(round(word(1), 9_400), 9))
        assertEquals(FeedReading.NoPrice(NoPriceReason.DECIMALS_CHANGED), reading())

        chain.snapshot = running(head = 10, headTimestamp = 10_012)
        answer(CallOutcome.Returned(word(8), 10), CallOutcome.Returned(round("f".repeat(64), 9_400), 10))
        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_POSITIVE), reading(), "a negative answer")

        chain.snapshot = running(head = 11, headTimestamp = 10_024)
        answer(CallOutcome.Returned(word(8), 11), CallOutcome.Reverted(11))
        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_A_FEED), reading())

        assertEquals(
            listOf<FeedReading>(FeedReading.NoPrice(NoPriceReason.NO_FEED)),
            feeds.observe(Network.SEPOLIA, PriceId("UNI")).toList(),
        )
    }

    @Test
    fun aRefusalEndsTheReadingAndAnUnknownHeadTimeIsUnproven() = runTest {
        chain.snapshot = running(head = 9, headTimestamp = null)
        answer(CallOutcome.Returned(word(8), 9), CallOutcome.Returned(round(word(1), 9_400), 9))
        assertEquals(
            FeedReading.Unavailable(ReadUnavailable.UNPROVEN),
            feeds().observe(Network.SEPOLIA, eth.priceId).first { it !is FeedReading.Syncing },
        )

        answer(CallOutcome.Refused, CallOutcome.Refused)
        assertEquals(
            listOf<FeedReading>(FeedReading.Unavailable(ReadUnavailable.REFUSED)),
            feeds().observe(Network.SEPOLIA, eth.priceId).toList(),
        )
    }

    @Test
    fun aRoundIsFiveWordsWithAnUpdateTime() {
        assertEquals(Abi.Round("5", 3), Abi.round(round(word(5), 3)))
        assertNull(Abi.round(word(5)), "one word is no round")
        assertEquals(Abi.Round(null, 3), Abi.round(round("8" + "0".repeat(63), 3)), "a negative answer")
    }

    @Test
    fun sepoliaPinsAFeedForEveryDisplayCurrencyItOffersAndEachPricedToken() {
        val ids = ChainlinkFeeds.of(Network.SEPOLIA).map { it.priceId.id }.toSet()

        assertEquals(setOf("ETH", "BTC", "LINK", "USDC", "EUR", "GBP", "JPY"), ids)
    }
}
