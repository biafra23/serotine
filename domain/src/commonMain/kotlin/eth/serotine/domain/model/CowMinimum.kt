package eth.serotine.domain.model

/**
 * The least a CoW order may buy, as docs/swaps.md, *The signed minimum*, sets it: never below
 * what the verified Uniswap quote would give, net of the gas the account would pay there, less
 * the tolerance, whatever CoW's own estimate says. Pure arithmetic over amounts in the bought
 * token's units; no price enters it.
 */
object CowMinimum {

    /**
     * The Uniswap net: what the verified Uniswap quote returns, [amountOut], less that swap's
     * expected network fee converted into the bought token, [feeOut]. Null when the fee takes all
     * of it: no floor stands, and CoW is not offered (docs/swaps.md, *The signed minimum*).
     */
    fun uniswapNet(amountOut: UInt256, feeOut: UInt256): UInt256? =
        amountOut.minusOrNull(feeOut)?.takeUnless { it.isZero }

    /** The floor: the Uniswap [net] less [tolerance], rounded down. */
    fun floor(net: UInt256, tolerance: SlippageTolerance): UInt256 = tolerance.minimumOf(net)

    /**
     * The order's `buyAmount`: CoW's [estimate] less [tolerance], and never below [floor]. With
     * the estimate at or above the Uniswap net the order keeps its whole tolerance below the
     * estimate; below it, the floor stands, and the order may well not fill.
     */
    fun buyAmount(estimate: UInt256, floor: UInt256, tolerance: SlippageTolerance): UInt256 =
        maxOf(tolerance.minimumOf(estimate), floor)
}
