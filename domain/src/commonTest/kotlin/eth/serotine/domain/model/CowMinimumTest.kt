package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CowMinimumTest {

    private val half = SlippageTolerance.DEFAULT

    @Test
    fun theUniswapNetIsTheQuoteLessItsFee() {
        assertEquals(UInt256.of(990), CowMinimum.uniswapNet(UInt256.of(1_000), UInt256.of(10)))
        assertEquals(UInt256.of(1_000), CowMinimum.uniswapNet(UInt256.of(1_000), UInt256.ZERO))
    }

    @Test
    fun aFeeThatTakesItAllLeavesNoNet() {
        assertNull(CowMinimum.uniswapNet(UInt256.of(1_000), UInt256.of(1_000)))
        assertNull(CowMinimum.uniswapNet(UInt256.of(1_000), UInt256.of(1_001)))
    }

    @Test
    fun anEstimateAboveTheNetKeepsItsWholeTolerance() {
        val floor = CowMinimum.floor(UInt256.of(1_000), half)
        assertEquals(UInt256.of(995), floor)
        // CoW estimates 1 200: the order signs 1 194, the tolerance below CoW's own figure.
        assertEquals(UInt256.of(1_194), CowMinimum.buyAmount(UInt256.of(1_200), floor, half))
    }

    @Test
    fun anEstimateBelowTheNetIsHeldToTheFloor() {
        val floor = CowMinimum.floor(UInt256.of(1_000), half)
        // CoW estimates 900, below Uniswap's 1 000 net: the order still signs at least 995.
        assertEquals(floor, CowMinimum.buyAmount(UInt256.of(900), floor, half))
        // At the net itself, the estimate less the tolerance is the floor.
        assertEquals(floor, CowMinimum.buyAmount(UInt256.of(1_000), floor, half))
    }
}
