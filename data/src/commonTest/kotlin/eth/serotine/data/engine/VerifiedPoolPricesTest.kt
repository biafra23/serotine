package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.data.prices.PinnedPool
import eth.serotine.data.prices.UniswapPools
import eth.serotine.data.swaps.PinnedUniswapV3
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedPoolPricesTest {

    private val factory = Address.of("0x" + "f1".repeat(20))
    private val weth = Address.of("0x" + "77".repeat(20))
    private val deployment = UniswapV3Deployment(
        factory,
        Address.of("0x" + "a1".repeat(20)),
        Address.of("0x" + "a2".repeat(20)),
        weth,
    )
    private val uniswap = PinnedUniswapV3(
        deployment,
        mapOf(factory to codeHash(FACTORY_CODE)) +
            listOf(deployment.quoterV2, deployment.swapRouter02, weth).associateWith { codeHash("6001") },
    )

    /** A token above WETH's address: WETH is the pool's token0, and its tick prices WETH in the token. */
    private val cow = PinnedPool(
        PriceId("COW"),
        Address.of("0x" + "de".repeat(20)),
        tokenDecimals = 18,
        fee = 10_000,
        pool = Address.of("0x" + "c0".repeat(20)),
        minQuoteHeld = 10,
    )

    /** A token below WETH's address, with 6 decimals: the token is token0. */
    private val low = PinnedPool(
        PriceId("LOW"),
        Address.of("0x" + "11".repeat(20)),
        tokenDecimals = 6,
        fee = 3_000,
        pool = Address.of("0x" + "c1".repeat(20)),
        minQuoteHeld = 10,
    )

    private val headTime = 2_000_000L
    private val chain = FakeChain().apply {
        snapshot = running(head = 9, headTimestamp = headTime)
        codes[factory] = ProvenCode(FACTORY_CODE, 9)
    }

    private fun TestScope.prices(pools: List<PinnedPool> = listOf(cow, low), pinned: PinnedUniswapV3? = uniswap) =
        VerifiedPoolPrices(Network.ETHEREUM, chain, StandardTestDispatcher(testScheduler), 4.seconds, pools, pinned)

    private fun word(n: Long) = (if (n < 0) (n.toULong()).toString(16).padStart(64, 'f') else n.toString(16))
        .padStart(64, '0')

    private fun returned(hex: String) = CallOutcome.Returned(hex, 9, "0x" + "09".repeat(32))

    /**
     * [pool] as the chain has it: named by the factory, holding [wethHeld] whole WETH, its tick now
     * [tickNow], [cardinality] observations of which the oldest was written [oldestAge] seconds
     * before the head, and an average tick of [averageTick] over the window ending [endingAgo]
     * seconds before the head.
     */
    private fun pool(
        pool: PinnedPool,
        wethHeld: Long = 26,
        tickNow: Long = 98_000,
        cardinality: Long = 76,
        oldestAge: Long = 200 * 3_600,
        averageTick: Long = 98_000,
        endingAgo: Long = 0,
        named: Address = pool.pool,
    ) {
        val (first, second) = if (pool.token.hex < weth.hex) pool.token to weth else weth to pool.token
        chain.calls[factory to VerifiedPoolPrices.getPool(first, second, pool.fee).toHex()] =
            returned(word(0).take(24) + named.hex.removePrefix("0x"))
        chain.calls[weth to Abi.balanceOf(pool.pool).toHex()] =
            returned(heldWord(wethHeld))
        val index = 5L
        chain.calls[pool.pool to SLOT0] = returned(
            word(0) + word(tickNow) + word(index) + word(cardinality) + word(cardinality) + word(0) + word(1),
        )
        chain.calls[pool.pool to VerifiedPoolPrices.observations(((index + 1) % cardinality).toInt()).toHex()] =
            returned(word(headTime - oldestAge) + word(0) + word(0) + word(1))
        val older = 1_000_000_000L
        chain.calls[pool.pool to VerifiedPoolPrices.observe(endingAgo + 1_800, endingAgo).toHex()] = returned(
            word(0x40) + word(0xa0) + word(2) + word(older) + word(older + averageTick * 1_800) +
                word(2) + word(0) + word(0),
        )
    }

    /** [whole] WETH as the token's balance word. */
    private fun heldWord(whole: Long): String {
        // whole × 10^18 does not fit a Long past 9 WETH: the word is written from its decimal digits.
        val decimal = whole.toString() + "0".repeat(18)
        var value = decimal.map { it - '0' }
        val hex = StringBuilder()
        while (value.any { it != 0 }) {
            var remainder = 0
            val next = mutableListOf<Int>()
            for (digit in value) {
                val current = remainder * 10 + digit
                next += current / 16
                remainder = current % 16
            }
            hex.append("0123456789abcdef"[remainder])
            value = next.dropWhile { it == 0 }
        }
        return hex.reverse().toString().padStart(64, '0')
    }

    private suspend fun VerifiedPoolPrices.now(id: PriceId) =
        observe(Network.ETHEREUM, id).first { it !is FeedReading.Syncing }

    @Test
    fun aPoolsAverageIsAPriceInWethDatedByTheHeadAndMarkedAsAnAverage() = runTest {
        pool(cow, averageTick = 98_000, tickNow = 98_100)

        val reading = prices().now(cow.priceId) as FeedReading.Verified
        // WETH is token0: the tick prices one WETH in COW, so a COW costs its inverse.
        assertClose(1 / 1.0001.pow(98_000), reading.price)
        assertEquals(headTime, reading.updatedAt)
        assertEquals(0, reading.ageSeconds)
        assertEquals(false, reading.stale)
        assertEquals(true, reading.poolAverage)
    }

    @Test
    fun aTokenBelowWethsAddressIsToken0AndItsDecimalsScaleTheTick() = runTest {
        // One LOW (6 decimals) at 2.0 WETH: 2 × 10^18 wei per 10^6 units, 2 × 10^12 a unit.
        val tick = (kotlin.math.ln(2e12) / kotlin.math.ln(1.0001)).toLong()
        pool(low, averageTick = tick, tickNow = tick)

        val reading = prices().now(low.priceId) as FeedReading.Verified
        assertClose(1.0001.pow(tick.toDouble()) * 1e-12, reading.price)
        assertTrue(reading.price in 1.99..2.01, "about 2 WETH, not ${reading.price}")
    }

    @Test
    fun aPoolThatKeepsOneObservationIsNoPriceItsAverageWouldBeItsSpotPrice() = runTest {
        pool(cow, cardinality = 1)

        assertEquals(FeedReading.NoPrice(NoPriceReason.POOL_HISTORY_SHORT), prices().now(cow.priceId))
    }

    @Test
    fun aPoolWhoseOldestObservationIsYoungerThanTheWindowIsNoPrice() = runTest {
        pool(cow, oldestAge = UniswapPools.WINDOW_SECONDS - 1)

        assertEquals(FeedReading.NoPrice(NoPriceReason.POOL_HISTORY_SHORT), prices().now(cow.priceId))
    }

    @Test
    fun aPoolThatHoldsLessWethThanItsFloorIsNoPrice() = runTest {
        pool(cow, wethHeld = 9)

        assertEquals(FeedReading.NoPrice(NoPriceReason.POOL_TOO_THIN), prices().now(cow.priceId))
    }

    @Test
    fun aPoolWhosePriceNowIsFarFromItsAverageIsNoPrice() = runTest {
        pool(cow, averageTick = 98_000, tickNow = 98_000 + UniswapPools.MAX_TICK_DIVERGENCE + 1)

        assertEquals(FeedReading.NoPrice(NoPriceReason.POOL_MOVED), prices().now(cow.priceId))
    }

    @Test
    fun aPoolTheFactoryDoesNotNameIsNoPool() = runTest {
        pool(cow, named = Address.of("0x" + "bb".repeat(20)))

        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_A_POOL), prices().now(cow.priceId))
    }

    @Test
    fun aFactoryWhoseProvenCodeIsNotItsPinNamesNoPool() = runTest {
        pool(cow)
        chain.codes[factory] = ProvenCode("6002", 9)

        assertEquals(FeedReading.NoPrice(NoPriceReason.NOT_A_POOL), prices().now(cow.priceId))
    }

    @Test
    fun aPriceIdWithNoPoolAndAnotherNetworkAreNoAnswer() = runTest {
        val prices = prices()

        assertEquals(
            FeedReading.NoPrice(NoPriceReason.NO_FEED),
            prices.observe(Network.ETHEREUM, PriceId("GNO")).first(),
        )
        assertEquals(
            FeedReading.Unavailable(ReadUnavailable.NO_ENGINE),
            prices.observe(Network.GNOSIS, cow.priceId).first(),
        )
        assertEquals(
            mapOf(cow.priceId to PriceId("ETH"), low.priceId to PriceId("ETH")),
            prices.quotes(Network.ETHEREUM),
        )
        assertEquals(emptyMap(), prices.quotes(Network.GNOSIS))
    }

    @Test
    fun aNetworkWithNoUniswapPinnedPricesNothing() = runTest {
        val prices = prices(pinned = null)

        assertEquals(emptyMap(), prices.quotes(Network.ETHEREUM))
        assertEquals(FeedReading.NoPrice(NoPriceReason.NO_FEED), prices.observe(Network.ETHEREUM, cow.priceId).first())
    }

    @Test
    fun aDayBackIsTheAverageOverTheWindowEndingThenFromTheObservationsAtTheHead() = runTest {
        pool(cow, averageTick = 97_000, endingAgo = 86_400)

        val past = prices().before(Network.ETHEREUM, cow.priceId, 86_400) as PastReading.Verified
        assertClose(1 / 1.0001.pow(97_000), past.price)
        assertEquals(headTime - 86_400, past.updatedAt)
        assertEquals(false, past.stale)
    }

    @Test
    fun aDayBackBeyondThePoolsObservationsIsNoRoundThatOld() = runTest {
        pool(cow, oldestAge = (2.8 * 3_600).toLong(), endingAgo = 86_400)

        assertEquals(PastReading.NoRoundThatOld, prices().before(Network.ETHEREUM, cow.priceId, 86_400))
    }

    @Test
    fun aDayBackThePoolRefusesToObserveIsNoRoundThatOld() = runTest {
        pool(cow, endingAgo = 86_400)
        chain.calls[cow.pool to VerifiedPoolPrices.observe(86_400 + 1_800, 86_400).toHex()] =
            CallOutcome.Reverted(9)

        assertEquals(PastReading.NoRoundThatOld, prices().before(Network.ETHEREUM, cow.priceId, 86_400))
    }

    @Test
    fun aSignedWordReadsBothSignsAndRefusesOneThatDoesNotExtendItsSign() {
        assertEquals(98_000L, VerifiedPoolPrices.signed(word(98_000)))
        assertEquals(-276_324L, VerifiedPoolPrices.signed(word(-276_324)))
        assertEquals(null, VerifiedPoolPrices.signed("1" + "0".repeat(47) + "0".repeat(16)))
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(kotlin.math.abs(actual / expected - 1) < 1e-9, "expected $expected, was $actual")

    private companion object {
        const val FACTORY_CODE = "6080"
        const val SLOT0 = "3850c7bd"
    }
}
