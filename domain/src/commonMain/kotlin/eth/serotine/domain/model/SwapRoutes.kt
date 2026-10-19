package eth.serotine.domain.model

/**
 * How a Uniswap swap's route is found (docs/swaps.md, *Finding a route*), from chain data alone:
 * the pinned factory's pools and their liquidity in range, then QuoterV2's verified quote of each
 * candidate. Uniswap's routing API is never asked; its answer would be a claim the light client
 * cannot prove. Pure: the reads are the caller's, in the order [poolsToRead], [candidates],
 * [best].
 */
object SwapRoutes {

    /**
     * The pools whose liquidity decides the candidates for selling [tokenIn] for [tokenOut]: their
     * own pool at every tier and, when neither is [weth], each leg through [weth] at every tier.
     * Each is read as the factory's `getPool` and, for one it lists, the pool's `liquidity()`.
     */
    fun poolsToRead(tokenIn: Address, tokenOut: Address, weth: Address): List<PoolKey> {
        require(tokenIn != tokenOut) { "a swap is of two different tokens" }
        val direct = FeeTier.entries.map { PoolKey.of(tokenIn, tokenOut, it) }
        if (tokenIn == weth || tokenOut == weth) return direct
        return direct + FeeTier.entries.map { PoolKey.of(tokenIn, weth, it) } +
            FeeTier.entries.map { PoolKey.of(weth, tokenOut, it) }
    }

    /**
     * The routes worth quoting for selling [tokenIn] for [tokenOut], given the [liquidity] in
     * range of the pools the factory lists (a pool it does not list is absent): the pair's own
     * pool at each tier, and one hop through [weth] with each leg at the tier with the most
     * liquidity in range (on a tie, the lower fee). A pool with none in range is skipped, and a
     * hop needs both its legs. Direct routes come first, by tier.
     *
     * Liquidity in range is a hint at which pool can take a swap, not a measure of what it
     * returns: the quotes decide ([best]).
     */
    fun candidates(
        tokenIn: Address,
        tokenOut: Address,
        weth: Address,
        liquidity: Map<PoolKey, UInt256>,
    ): List<SwapRoute> {
        require(tokenIn != tokenOut) { "a swap is of two different tokens" }
        val direct = FeeTier.entries
            .filter { liquidity.inRange(PoolKey.of(tokenIn, tokenOut, it)) }
            .map { SwapRoute.direct(tokenIn, tokenOut, it) }
        if (tokenIn == weth || tokenOut == weth) return direct
        val feeIn = liquidity.deepestTier(tokenIn, weth) ?: return direct
        val feeOut = liquidity.deepestTier(weth, tokenOut) ?: return direct
        return direct + SwapRoute.via(tokenIn, feeIn, weth, feeOut, tokenOut)
    }

    /**
     * The quote of [quotes] that returns the most once [costOf] it (the swap's expected network
     * fee, in the bought token's units) is taken off, so a one-hop route that only grosses more
     * does not beat a direct one; on a tie, the one through fewer pools, then the earlier. A net
     * below zero still ranks, below every one that is not. Null when there are no quotes.
     *
     * A Safe's swap, paid for by whoever executes it, compares gross: a [costOf] of zero.
     */
    fun best(quotes: List<RouteQuote>, costOf: (RouteQuote) -> UInt256): RouteQuote? {
        var best: Ranked? = null
        for (quote in quotes) {
            val ranked = Ranked(quote, Net.of(quote.amountOut, costOf(quote)))
            if (best == null || ranked.beats(best)) best = ranked
        }
        return best?.quote
    }

    private fun Map<PoolKey, UInt256>.inRange(key: PoolKey): Boolean = this[key]?.isZero == false

    private fun Map<PoolKey, UInt256>.deepestTier(tokenA: Address, tokenB: Address): FeeTier? = FeeTier.entries
        .mapNotNull { tier -> this[PoolKey.of(tokenA, tokenB, tier)]?.takeUnless { it.isZero }?.let { tier to it } }
        // maxBy keeps the first of equals, and the tiers come by fee: on a tie, the lower fee.
        .maxByOrNull { it.second }
        ?.first

    /** A quote and its [net], as [best] ranks them. */
    private class Ranked(val quote: RouteQuote, val net: Net) {
        fun beats(other: Ranked): Boolean {
            val order = net.compareTo(other.net)
            return order > 0 || (order == 0 && quote.route.pools < other.quote.route.pools)
        }
    }

    /** An amount less a cost, which may fall below zero: its [magnitude], [negative] or not. */
    private class Net(val negative: Boolean, val magnitude: UInt256) : Comparable<Net> {
        override fun compareTo(other: Net): Int = when {
            negative != other.negative -> if (negative) -1 else 1
            negative -> other.magnitude.compareTo(magnitude)
            else -> magnitude.compareTo(other.magnitude)
        }

        companion object {
            fun of(amount: UInt256, cost: UInt256): Net =
                amount.minusOrNull(cost)?.let { Net(false, it) } ?: Net(true, cost - amount)
        }
    }
}
