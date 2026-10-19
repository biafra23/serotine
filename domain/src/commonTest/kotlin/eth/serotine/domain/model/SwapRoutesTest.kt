package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SwapRoutesTest {
    private val usdc = Address.of("0x" + "1c".repeat(20))
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val link = Address.of("0x" + "77".repeat(20))

    private fun liquid(vararg pools: Pair<PoolKey, Long>): Map<PoolKey, UInt256> =
        pools.associate { (key, liquidity) -> key to UInt256.of(liquidity) }

    @Test
    fun theDirectPairIsReadAtEveryTierAndEachLegThroughWeth() {
        val pools = SwapRoutes.poolsToRead(usdc, link, weth)
        assertEquals(12, pools.size)
        assertEquals(FeeTier.entries.map { PoolKey.of(usdc, link, it) }, pools.take(4))
        assertEquals(FeeTier.entries.map { PoolKey.of(usdc, weth, it) }, pools.subList(4, 8))
        assertEquals(FeeTier.entries.map { PoolKey.of(weth, link, it) }, pools.drop(8))
    }

    @Test
    fun aPairWithWethHasOnlyItsOwnPools() {
        assertEquals(FeeTier.entries.map { PoolKey.of(usdc, weth, it) }, SwapRoutes.poolsToRead(usdc, weth, weth))
        assertEquals(FeeTier.entries.map { PoolKey.of(weth, link, it) }, SwapRoutes.poolsToRead(weth, link, weth))
        assertFailsWith<IllegalArgumentException> { SwapRoutes.poolsToRead(usdc, usdc, weth) }
    }

    @Test
    fun everyDirectPoolWithLiquidityInRangeIsACandidateByTier() {
        val liquidity = liquid(
            PoolKey.of(usdc, link, FeeTier.HIGH) to 5,
            PoolKey.of(usdc, link, FeeTier.LOW) to 9,
            PoolKey.of(usdc, link, FeeTier.MEDIUM) to 0,
        )
        assertEquals(
            listOf(SwapRoute.direct(usdc, link, FeeTier.LOW), SwapRoute.direct(usdc, link, FeeTier.HIGH)),
            SwapRoutes.candidates(usdc, link, weth, liquidity),
        )
    }

    @Test
    fun aHopTakesEachLegsDeepestTierOnATieTheLowerFee() {
        val liquidity = liquid(
            PoolKey.of(usdc, weth, FeeTier.LOW) to 10,
            PoolKey.of(usdc, weth, FeeTier.MEDIUM) to 30,
            PoolKey.of(weth, link, FeeTier.LOW) to 7,
            PoolKey.of(weth, link, FeeTier.HIGH) to 7,
            PoolKey.of(weth, link, FeeTier.LOWEST) to 0,
        )
        assertEquals(
            listOf(SwapRoute.via(usdc, FeeTier.MEDIUM, weth, FeeTier.LOW, link)),
            SwapRoutes.candidates(usdc, link, weth, liquidity),
        )
    }

    @Test
    fun aHopNeedsBothLegsInRange() {
        val direct = PoolKey.of(usdc, link, FeeTier.MEDIUM) to 1L
        val liquidity = liquid(
            direct,
            PoolKey.of(usdc, weth, FeeTier.LOW) to 10,
            PoolKey.of(weth, link, FeeTier.LOW) to 0,
        )
        assertEquals(
            listOf(SwapRoute.direct(usdc, link, FeeTier.MEDIUM)),
            SwapRoutes.candidates(usdc, link, weth, liquidity),
        )
        assertEquals(emptyList(), SwapRoutes.candidates(usdc, link, weth, emptyMap()))
    }

    @Test
    fun aPairWithWethHasNoHop() {
        val liquidity = liquid(PoolKey.of(usdc, weth, FeeTier.LOW) to 10, PoolKey.of(weth, link, FeeTier.LOW) to 10)
        assertEquals(
            listOf(SwapRoute.direct(usdc, weth, FeeTier.LOW)),
            SwapRoutes.candidates(usdc, weth, weth, liquidity),
        )
    }

    @Test
    fun theLargestNetWinsSoAHopThatOnlyGrossesMoreLoses() {
        val direct = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), UInt256.of(1_000), gasEstimate = 100)
        val hop = RouteQuote(
            SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.LOW, link),
            UInt256.of(1_015),
            gasEstimate = 200,
        )
        // A gas unit costs a fifth of a unit of the token bought: 20 for the direct route, 40 for the hop.
        val cost = { quote: RouteQuote -> UInt256.of(quote.gasEstimate / 5) }

        assertEquals(direct, SwapRoutes.best(listOf(hop, direct), cost), "the direct route nets 980, the hop 975")
        assertEquals(hop, SwapRoutes.best(listOf(direct, hop)) { UInt256.ZERO }, "gross, as a Safe's swap compares")
    }

    @Test
    fun onATieTheRouteThroughFewerPoolsThenTheEarlierWins() {
        val direct = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), UInt256.of(1_000), 0)
        val other = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.HIGH), UInt256.of(1_000), 0)
        val hop = RouteQuote(SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.LOW, link), UInt256.of(1_000), 0)

        assertEquals(direct, SwapRoutes.best(listOf(hop, direct, other)) { UInt256.ZERO })
        assertEquals(other, SwapRoutes.best(listOf(other, direct)) { UInt256.ZERO })
    }

    @Test
    fun aNetBelowZeroStillRanksBelowEveryOneAboveIt() {
        val small = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.MEDIUM), UInt256.of(10), 0)
        val smaller = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.HIGH), UInt256.of(5), 0)
        val zero = RouteQuote(SwapRoute.direct(usdc, link, FeeTier.LOW), UInt256.of(50), 0)

        // Costs of 30: 10 is 20 short, 5 is 25 short, and 50 nets 20.
        assertEquals(small, SwapRoutes.best(listOf(smaller, small)) { UInt256.of(30) })
        assertEquals(zero, SwapRoutes.best(listOf(small, zero, smaller)) { UInt256.of(30) })
        assertNull(SwapRoutes.best(emptyList()) { UInt256.ZERO })
    }
}
