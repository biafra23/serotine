package eth.serotine.domain.model

/**
 * A swap's price impact (docs/swaps.md, *The transaction*): what its route would return at the
 * spot prices its pools' `slot0` holds, less each pool's fee, against what QuoterV2 quoted for
 * the exact amount. Both are chain data, read with verified calls, and the figure is one the
 * review shows: no amount, and no minimum, ever comes from it.
 */
object PriceImpact {
    /** 2^96: a Q64.96's one. */
    private val Q96 = UInt256.of("79228162514264337593543950336")

    /** 2^192: one, squared, in Q64.96. */
    private val Q192 = UInt256.of("6277101735386680763835789423207666416102355444464034512896")

    /** A fee tier's denominator: its pips are millionths. */
    private const val PIPS = 1_000_000

    /** Basis points in one. */
    private const val BASIS_POINTS = 10_000L

    /**
     * What [route] would return for [amountIn] at the spot prices [prices] (one per pool, in
     * the route's order), less each pool's fee, rounded down at every step; null when a price
     * is zero or the amount leaves 256 bits.
     */
    fun spotOut(route: SwapRoute, amountIn: UInt256, prices: List<PoolSlot0>): UInt256? {
        require(prices.size == route.pools) { "one price per pool" }
        var amount = amountIn
        for (i in route.fees.indices) {
            val sqrt = prices[i].sqrtPriceX96
            if (sqrt.isZero) return null
            // The pool's price is token1 per token0, its token0 the lower address.
            val zeroForOne = route.tokens[i].hex < route.tokens[i + 1].hex
            val squared = sqrt.timesOrNull(sqrt)
            val atSpot = when {
                zeroForOne && squared != null -> amount.mulDivOrNull(squared, Q192)
                zeroForOne -> amount.mulDivOrNull(sqrt, Q96)?.mulDivOrNull(sqrt, Q96)
                squared != null -> amount.mulDivOrNull(Q192, squared)
                // sqrt ≥ 2^128, so 2^192 / sqrt keeps at least 32 bits: the price, to a 2^-32.
                else -> Q192.mulDivOrNull(UInt256.ONE, sqrt)?.let { amount.mulDivOrNull(it, sqrt) }
            } ?: return null
            amount = atSpot.mulDivOrNull(UInt256.of((PIPS - route.fees[i].pips).toLong()), UInt256.of(PIPS.toLong()))
                ?: return null
        }
        return amount
    }

    /**
     * How far [quotedOut] falls below [spotOut], in basis points rounded down; null when [spotOut]
     * is zero, which no impact is measured against, or [quotedOut] stands a basis point or more
     * above it: no quote of one state of the pools returns more than their spot prices, so the
     * two are not of one state. Within a basis point above, it is the rounding of each, and 0.
     */
    fun basisPoints(spotOut: UInt256, quotedOut: UInt256): Int? {
        if (spotOut.isZero) return null
        val short = spotOut.minusOrNull(quotedOut)
            ?: return 0.takeIf { (quotedOut - spotOut).mulDivOrNull(UInt256.of(BASIS_POINTS), spotOut)?.isZero == true }
        // short ≤ spotOut, so the quotient is at most 10 000.
        return short.mulDivOrNull(UInt256.of(BASIS_POINTS), spotOut)?.toLongOrNull()?.toInt()
    }
}
