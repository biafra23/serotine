package eth.serotine.domain.model

/**
 * The Uniswap v3 contracts a swap on one network goes through (docs/swaps.md, *Pins*): the
 * [factory] whose `getPool` is the only source of a pool's address, [quoterV2] whose verified run
 * quotes a route, [swapRouter02], which the account calls and approves, and [weth9], the wrapped
 * ether that native ETH goes in and out through and that a one-hop route goes via.
 *
 * Pinned per network by :data, each address with the runtime code hash it must have; a pin
 * whose code differs on chain is no venue. An address from anywhere else is never treated as
 * one of these, and a pool is a pool only when [factory] returns it.
 */
data class UniswapV3Deployment(
    val factory: Address,
    val quoterV2: Address,
    val swapRouter02: Address,
    val weth9: Address,
)

/**
 * A Uniswap v3 fee tier: what a pool charges per swap, in [pips] (hundredths of a basis point,
 * as the factory's `getPool` and a route's path take it), every one a route may use
 * (docs/swaps.md, *Finding a route*). The factory's constructor enables 0.05 %, 0.3 % and 1 %;
 * 0.01 % is enabled afterwards by its owner, and where it is not, `getPool` lists no pool there.
 */
enum class FeeTier(val pips: Int) {
    /** 0.01 %. */
    LOWEST(100),

    /** 0.05 %. */
    LOW(500),

    /** 0.3 %. */
    MEDIUM(3_000),

    /** 1 %. */
    HIGH(10_000),
    ;

    companion object {
        /** The tier of [pips], or null when no tier charges that. */
        fun ofPips(pips: Int): FeeTier? = entries.firstOrNull { it.pips == pips }
    }
}

/**
 * A pool as the pinned factory knows it: of two tokens at [fee]. `getPool` takes the tokens in
 * either order, so a key holds them sorted ([token0] below [token1], as the pool itself orders
 * them) and one pool has one key.
 */
@ConsistentCopyVisibility
data class PoolKey private constructor(val token0: Address, val token1: Address, val fee: FeeTier) {

    companion object {
        /** The pool of [tokenA] and [tokenB], in either order, at [fee]; the two are different tokens. */
        fun of(tokenA: Address, tokenB: Address, fee: FeeTier): PoolKey {
            require(tokenA != tokenB) { "a pool is of two different tokens" }
            return if (tokenA.hex < tokenB.hex) PoolKey(tokenA, tokenB, fee) else PoolKey(tokenB, tokenA, fee)
        }
    }
}

/**
 * The pools a swap goes through, in order: from [tokens]' first to its last, through the pool
 * of each pair of neighbours at the matching tier of [fees]. One pool ([isDirect]) or one hop
 * through a middle token, never more (docs/swaps.md, *Finding a route*: routes of more than one
 * hop and split routes are what verifying every quote gives up). No token follows itself, and a
 * route never ends where it started.
 */
data class SwapRoute(val tokens: List<Address>, val fees: List<FeeTier>) {
    init {
        require(tokens.size in 2..MAX_TOKENS) { "a route has one or two pools, not ${tokens.size - 1}" }
        require(fees.size == tokens.size - 1) { "a route has one fee tier per pool" }
        require(tokens.zipWithNext().none { (a, b) -> a == b }) { "a pool is of two different tokens" }
        require(tokens.first() != tokens.last()) { "a route ends with another token than it starts" }
    }

    /** The token the swap sells. */
    val tokenIn: Address
        get() = tokens.first()

    /** The token the swap buys. */
    val tokenOut: Address
        get() = tokens.last()

    /** How many pools the swap goes through. */
    val pools: Int
        get() = fees.size

    /** Whether the swap goes through one pool alone. */
    val isDirect: Boolean
        get() = pools == 1

    /** The pools, in the order the swap goes through them. */
    val poolKeys: List<PoolKey>
        get() = fees.indices.map { PoolKey.of(tokens[it], tokens[it + 1], fees[it]) }

    /**
     * The route as QuoterV2's `quoteExactInput` and SwapRouter02's `exactInput` take it: each
     * token's twenty bytes, with each pool's fee in pips as three bytes between its two tokens
     * (token ‖ fee ‖ token ‖ fee ‖ token).
     */
    fun path(): ByteArray {
        var path = tokens.first().toBytes()
        for (i in fees.indices) path += fees[i].pips.toUInt24() + tokens[i + 1].toBytes()
        return path
    }

    companion object {
        /** The most tokens a route goes through: one hop. */
        const val MAX_TOKENS = 3

        /** The swap of [tokenIn] for [tokenOut] in their pool at [fee]. */
        fun direct(tokenIn: Address, tokenOut: Address, fee: FeeTier): SwapRoute =
            SwapRoute(listOf(tokenIn, tokenOut), listOf(fee))

        /** The swap of [tokenIn] for [middle] at [feeIn], then of that for [tokenOut] at [feeOut]. */
        fun via(tokenIn: Address, feeIn: FeeTier, middle: Address, feeOut: FeeTier, tokenOut: Address): SwapRoute =
            SwapRoute(listOf(tokenIn, middle, tokenOut), listOf(feeIn, feeOut))
    }
}

/** [this], which fits, as three big-endian bytes: a uint24 as a packed path holds it. */
private fun Int.toUInt24(): ByteArray = byteArrayOf((this shr 16).toByte(), (this shr 8).toByte(), toByte())
