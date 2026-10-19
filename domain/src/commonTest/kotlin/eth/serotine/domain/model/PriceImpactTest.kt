package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceImpactTest {
    // token0 of every pair below is the lower address.
    private val low = Address.of("0x" + "11".repeat(20))
    private val mid = Address.of("0x" + "22".repeat(20))
    private val high = Address.of("0x" + "33".repeat(20))
    private val q96 = UInt256.of("79228162514264337593543950336")

    private fun price(sqrtTimesQ96: Long) = PoolSlot0(q96 * UInt256.of(sqrtTimesQ96), tick = 0)

    @Test
    fun aPoolAtPriceOneReturnsTheAmountLessItsFee() {
        val route = SwapRoute.direct(low, high, FeeTier.MEDIUM)
        assertEquals(UInt256.of(997_000), PriceImpact.spotOut(route, UInt256.of(1_000_000), listOf(price(1))))
    }

    @Test
    fun theDirectionDecidesWhetherThePriceMultipliesOrDivides() {
        // sqrt = 2: four of token1 per token0.
        val sell0 = SwapRoute.direct(low, high, FeeTier.LOWEST)
        val sell1 = SwapRoute.direct(high, low, FeeTier.LOWEST)
        assertEquals(UInt256.of(3_999_600), PriceImpact.spotOut(sell0, UInt256.of(1_000_000), listOf(price(2))))
        assertEquals(UInt256.of(249_975), PriceImpact.spotOut(sell1, UInt256.of(1_000_000), listOf(price(2))))
    }

    @Test
    fun aHopAppliesBothPoolsInOrder() {
        // low → mid at 4 per token0, then mid → high at 9: 36, less 0.05 % twice.
        val route = SwapRoute.via(low, FeeTier.LOW, mid, FeeTier.LOW, high)
        assertEquals(
            UInt256.of(35_964_009),
            PriceImpact.spotOut(route, UInt256.of(1_000_000), listOf(price(2), price(3))),
        )
    }

    @Test
    fun aPriceAbove128BitsKeepsItsPrecision() {
        // sqrt = 2^64 × 2^96, a price of 2^128 token1 per token0: selling token1 divides by it.
        val sqrt = q96 * UInt256.of("18446744073709551616")
        val route = SwapRoute.direct(high, low, FeeTier.LOWEST)
        val amountIn = UInt256.of("340282366920938463463374607431768211456000000") // 2^128 × 10^6
        assertEquals(UInt256.of(999_900), PriceImpact.spotOut(route, amountIn, listOf(PoolSlot0(sqrt, 0))))
    }

    @Test
    fun aZeroPriceGivesNoSpot() {
        val route = SwapRoute.direct(low, high, FeeTier.MEDIUM)
        assertNull(PriceImpact.spotOut(route, UInt256.of(1), listOf(PoolSlot0(UInt256.ZERO, 0))))
    }

    @Test
    fun theImpactIsHowFarTheQuoteFallsBelowTheSpotInBasisPoints() {
        assertEquals(100, PriceImpact.basisPoints(UInt256.of(1_000_000), UInt256.of(990_000)))
        assertEquals(0, PriceImpact.basisPoints(UInt256.of(1_000_000), UInt256.of(999_999)), "rounded down")
        assertEquals(0, PriceImpact.basisPoints(UInt256.of(1_000_000), UInt256.of(1_000_001)), "rounding")
        assertNull(PriceImpact.basisPoints(UInt256.of(1_000_000), UInt256.of(1_000_100)), "not of one state")
        assertEquals(10_000, PriceImpact.basisPoints(UInt256.of(1_000_000), UInt256.ZERO))
        assertNull(PriceImpact.basisPoints(UInt256.ZERO, UInt256.ONE))
    }
}
