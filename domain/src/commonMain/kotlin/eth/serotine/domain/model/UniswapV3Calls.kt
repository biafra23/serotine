package eth.serotine.domain.model

import eth.serotine.domain.model.AbiWords.WORD
import eth.serotine.domain.model.AbiWords.dynamic
import eth.serotine.domain.model.AbiWords.selector
import eth.serotine.domain.model.AbiWords.word

/**
 * The calls of the pinned `UniswapV3Factory` (docs/swaps.md, *Pins*), and the reading of what
 * they return: a pool is a pool only when the factory returns it in a verified call.
 */
object UniswapV3Factory {
    private val GET_POOL = selector("getPool(address,address,uint24)")

    /** The call data of `getPool(tokenA, tokenB, fee)` for the pool [key] names. */
    fun getPool(key: PoolKey): ByteArray = GET_POOL + word(key.token0) + word(key.token1) + word(key.fee.pips.toLong())

    /**
     * The pool `getPool` returned as [returnHex]: its address, the zero address when the factory
     * has no such pool, or null when it returned anything but one address word.
     */
    fun poolOf(returnHex: String): Address? = AbiWords.returnedWord(returnHex)?.let(AbiWords::address)
}

/** What a Uniswap v3 pool's `slot0()` says: its current [sqrtPriceX96] (a Q64.96) and [tick]. */
data class PoolSlot0(val sqrtPriceX96: UInt256, val tick: Int)

/**
 * The calls of a Uniswap v3 pool the factory returned, and the reading of what they return: the
 * liquidity in range, which decides a one-hop route's tiers (*Finding a route*), and `slot0`,
 * the spot price a quote's price impact is measured against (*The transaction*).
 */
object UniswapV3Pool {
    private val LIQUIDITY = selector("liquidity()")
    private val SLOT0 = selector("slot0()")

    /** `slot0` returns seven words: the price, the tick, three observation fields, the protocol fee, the lock. */
    private const val SLOT0_WORDS = 7

    private const val UINT128 = 128
    private const val UINT160 = 160
    private const val INT24 = 24

    /** The call data of `liquidity()`. */
    fun liquidity(): ByteArray = LIQUIDITY.copyOf()

    /** The liquidity in range `liquidity()` returned as [returnHex], or null when that is no uint128 word. */
    fun liquidityOf(returnHex: String): UInt256? =
        AbiWords.returnedWord(returnHex)?.let { AbiWords.uint(it, UINT128) }

    /** The call data of `slot0()`. */
    fun slot0(): ByteArray = SLOT0.copyOf()

    /**
     * The price and tick `slot0()` returned as [returnHex], or null when it is not its seven
     * words, or its first two are not a uint160 and an int24.
     */
    fun slot0Of(returnHex: String): PoolSlot0? {
        val words = AbiWords.returned(returnHex)?.let { AbiWords.words(it, SLOT0_WORDS) } ?: return null
        return PoolSlot0(
            sqrtPriceX96 = AbiWords.uint(words[0], UINT160) ?: return null,
            tick = AbiWords.int(words[1], INT24) ?: return null,
        )
    }
}

/**
 * QuoterV2's answer for a route: the [amountOut] the swap would return, and the [gasEstimate]
 * of the swaps in the pools, which the router's own work comes on top of.
 */
data class RouteQuote(val route: SwapRoute, val amountOut: UInt256, val gasEstimate: Long)

/**
 * The calls of the pinned `QuoterV2`, which quotes an exact input by running the swap and
 * reverting it, and the reading of what it returns. Its functions are not `view`, but run as a
 * verified call (an `eth_call` over proven state, in the engine's own EVM) they change nothing
 * and answer: the quote is chain data, not a price (docs/swaps.md, *What both venues share*).
 */
object QuoterV2 {
    private val QUOTE_EXACT_INPUT_SINGLE = selector("quoteExactInputSingle((address,address,uint256,uint24,uint160))")
    private val QUOTE_EXACT_INPUT = selector("quoteExactInput(bytes,uint256)")

    /** `quoteExactInputSingle` returns four words: the amount, the price after, the ticks crossed, the gas. */
    private const val SINGLE_WORDS = 4

    /** `quoteExactInput` returns the amount, two arrays (one entry per pool) and the gas: four head words. */
    private const val PATH_HEAD_WORDS = 4

    private const val UINT32 = 32
    private const val UINT160 = 160

    /**
     * The call data that quotes selling exactly [amountIn] of [route]'s first token along it:
     * `quoteExactInputSingle` with no price limit for one pool, `quoteExactInput` with the
     * route's path for one hop.
     */
    fun quote(route: SwapRoute, amountIn: UInt256): ByteArray =
        if (route.isDirect) {
            QUOTE_EXACT_INPUT_SINGLE +
                word(route.tokenIn) +
                word(route.tokenOut) +
                amountIn.toBytes32() +
                word(route.fees.single().pips.toLong()) +
                word(0)
        } else {
            QUOTE_EXACT_INPUT + word((2 * WORD).toLong()) + amountIn.toBytes32() + dynamic(route.path())
        }

    /**
     * What [quote] of [route] returned as [returnHex], or null when it is not exactly what that
     * function returns for a route of that many pools — every word of its type, each array of
     * one entry per pool, each offset where the ABI puts it — or the gas does not fit a [Long].
     */
    fun quoteOf(route: SwapRoute, returnHex: String): RouteQuote? {
        val data = AbiWords.returned(returnHex) ?: return null
        return if (route.isDirect) single(route, data) else path(route, data)
    }

    private fun single(route: SwapRoute, data: ByteArray): RouteQuote? {
        val (amountOut, priceAfter, ticksCrossed, gas) = AbiWords.words(data, SINGLE_WORDS) ?: return null
        AbiWords.uint(priceAfter, UINT160) ?: return null
        AbiWords.uint(ticksCrossed, UINT32) ?: return null
        return RouteQuote(route, AbiWords.uint(amountOut), AbiWords.long(gas) ?: return null)
    }

    private fun path(route: SwapRoute, data: ByteArray): RouteQuote? {
        val pools = route.pools
        // The head, then each array as its length and one word per pool.
        val pricesAt = PATH_HEAD_WORDS * WORD
        val ticksAt = pricesAt + (1 + pools) * WORD
        if (data.size != ticksAt + (1 + pools) * WORD) return null
        val words = AbiWords.words(data, data.size / WORD) ?: return null
        val (amountOut, pricesOffset, ticksOffset, gas) = words
        if (AbiWords.long(pricesOffset) != pricesAt.toLong() || AbiWords.long(ticksOffset) != ticksAt.toLong()) {
            return null
        }
        val prices = words.subList(pricesAt / WORD, ticksAt / WORD)
        val ticks = words.subList(ticksAt / WORD, words.size)
        if (AbiWords.long(prices.first()) != pools.toLong() || AbiWords.long(ticks.first()) != pools.toLong()) {
            return null
        }
        if (prices.drop(1).any { AbiWords.uint(it, UINT160) == null }) return null
        if (ticks.drop(1).any { AbiWords.uint(it, UINT32) == null }) return null
        return RouteQuote(route, AbiWords.uint(amountOut), AbiWords.long(gas) ?: return null)
    }
}

/**
 * A call to a contract as a transaction from the account sends it: [value] (in wei) to [to]
 * with [data]. Equal by value, [data] included; holds a copy of the data and hands out copies.
 * (Not a Safe transaction's decoded call, `SafeTxAction.ContractCall`.)
 */
class TransactionCall(val to: Address, val value: UInt256, data: ByteArray) {
    private val bytes = data.copyOf()

    /** The call data: a copy. */
    val data: ByteArray
        get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is TransactionCall && to == other.to && value == other.value && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * to.hashCode() + value.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "TransactionCall(to=$to, value=$value, ${bytes.size} bytes)"
}

/**
 * The swap the account sends to the pinned `SwapRouter02` (docs/swaps.md, *The transaction*):
 * one `multicall(deadline, data)`, so that the router refuses it once the chain is past the
 * deadline, holding `exactInputSingle` for one pool or `exactInput` for one hop, with no price
 * limit, and `unwrapWETH9` after it when the account buys ETH.
 */
object SwapRouter02 {
    private val MULTICALL = selector("multicall(uint256,bytes[])")
    private val EXACT_INPUT_SINGLE =
        selector("exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))")
    private val EXACT_INPUT = selector("exactInput((bytes,address,uint256,uint256))")
    private val UNWRAP_WETH9 = selector("unwrapWETH9(uint256,address)")

    /** `exactInput`'s tuple has four head words: the path's offset, the recipient, the two amounts. */
    private const val EXACT_INPUT_HEAD_WORDS = 4

    private const val SELECTOR_SIZE = 4
    private const val ADDRESS_SIZE = 20
    private const val FEE_SIZE = 3

    /** A swap's multicall holds the swap, and the unwrap when it buys ETH: never more. */
    private const val MAX_CALLS = 2

    /**
     * The latest deadline a decoded swap may carry: the last second of the year 9999. A later one
     * is no swap anyone builds, and no date a screen can show.
     */
    const val MAX_DEADLINE = 253_402_300_799L

    /**
     * The recipient that means the router itself (`Constants.ADDRESS_THIS`, address 2): where the
     * WETH a swap buys stays until `unwrapWETH9` sends it on as ETH in the same multicall.
     */
    val ADDRESS_THIS: Address = Address.of("0x" + "0".repeat(39) + "2")

    /**
     * The call that sells exactly [amountIn] of [sell] for at least [amountOutMinimum] of [buy]
     * along [route] through [deployment]'s router, paid to [account] and refused by the router
     * after [deadline] (seconds since the epoch, as a block's timestamp). [amountOutMinimum] is
     * not zero: a minimum of nothing would let the swap return nothing.
     *
     * [route] goes from [sell]'s token to [buy]'s, ETH being [deployment]'s WETH9:
     * - selling ETH, the call's value is [amountIn], which the router wraps;
     * - buying ETH, the router keeps the WETH ([ADDRESS_THIS]) and `unwrapWETH9(amountOutMinimum,
     *   account)` follows, which sends it all to [account] as ETH, or reverts below the minimum;
     * - otherwise the call carries no value and the router pays [account] directly.
     *
     * ETH for WETH and back is a wrap, not a swap, and no route has it.
     */
    fun swap(
        deployment: UniswapV3Deployment,
        sell: TransferAsset,
        buy: TransferAsset,
        route: SwapRoute,
        amountIn: UInt256,
        amountOutMinimum: UInt256,
        account: Address,
        deadline: Long,
    ): TransactionCall {
        require(route.tokenIn == sell.tokenOn(deployment)) { "the route starts with another token than sold" }
        require(route.tokenOut == buy.tokenOn(deployment)) { "the route ends with another token than bought" }
        require(!amountOutMinimum.isZero) { "a swap is never signed without a minimum" }
        require(deadline >= 0) { "a deadline is not negative" }
        return encode(
            deployment.swapRouter02,
            route,
            amountIn,
            amountOutMinimum,
            account,
            deadline,
            sellsEther = sell == TransferAsset.Native,
            buysEther = buy == TransferAsset.Native,
        )
    }

    private fun encode(
        router: Address,
        route: SwapRoute,
        amountIn: UInt256,
        amountOutMinimum: UInt256,
        account: Address,
        deadline: Long,
        sellsEther: Boolean,
        buysEther: Boolean,
    ): TransactionCall {
        val recipient = if (buysEther) ADDRESS_THIS else account
        val swap = if (route.isDirect) {
            exactInputSingle(route, recipient, amountIn, amountOutMinimum)
        } else {
            exactInput(route, recipient, amountIn, amountOutMinimum)
        }
        val calls = if (buysEther) listOf(swap, unwrapWeth9(amountOutMinimum, account)) else listOf(swap)
        val value = if (sellsEther) amountIn else UInt256.ZERO
        val data = AbiWords.withBytesArray(MULTICALL, head = listOf(word(deadline)), elements = calls)
        return TransactionCall(router, value, data)
    }

    /**
     * The swap [call] makes for [account] when it is exactly what [swap] builds for some route,
     * amounts and deadline, sent to the router at [call]'s `to`; null for anything else. Decoded
     * by its fields, then built again from them and held to the bytes and value it came with, so
     * only that exact encoding is read as a swap. Which router it is, and so whether it is
     * Uniswap's, is for the caller to establish: this reads the call, not the contract. A
     * deadline past [MAX_DEADLINE] is no swap's. Never throws: whatever the bytes, a swap or null.
     */
    fun decode(call: TransactionCall, account: Address): RouterSwap? = try {
        decodeOrNull(call, account)
    } catch (e: IllegalArgumentException) {
        null // a bound this reading missed: still no swap
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun decodeOrNull(call: TransactionCall, account: Address): RouterSwap? {
        val data = call.data
        if (data.size < SELECTOR_SIZE + 2 * WORD || !data.copyOf(SELECTOR_SIZE).contentEquals(MULTICALL)) return null
        val deadline = AbiWords.uint(data.copyOfRange(SELECTOR_SIZE, SELECTOR_SIZE + WORD)).toLongOrNull()
            ?.takeIf { it <= MAX_DEADLINE } ?: return null
        val calls = bytesArray(data, at = SELECTOR_SIZE + 2 * WORD) ?: return null
        val (route, amounts) = swapCall(calls[0]) ?: return null
        val (amountIn, minimum) = amounts
        if (minimum.isZero) return null
        val buysEther = calls.size == 2
        val sellsEther = !call.value.isZero
        if (buysEther && sellsEther) return null
        val decoded = RouterSwap(
            router = call.to,
            route = route,
            sellsEther = sellsEther,
            amountIn = amountIn,
            buysEther = buysEther,
            minimumOut = minimum,
            deadline = deadline,
        )
        val rebuilt = runCatching {
            encode(call.to, route, amountIn, minimum, account, deadline, sellsEther, buysEther)
        }.getOrNull() ?: return null
        // The recipient is in the bytes compared: the account, or the router itself when it unwraps.
        return decoded.takeIf { rebuilt == call }
    }

    /**
     * The one or two calls of the `bytes[]` whose length word starts at [at] in [data], its
     * offsets relative to the word after it; null when it is not one, or holds another count.
     * Every offset and length is bounded by [data]'s size before it is used.
     */
    private fun bytesArray(data: ByteArray, at: Int): List<ByteArray>? {
        val count = boundedAt(data, at, MAX_CALLS) ?: return null
        if (count < 1) return null
        val base = at + WORD
        return (0 until count).map { i ->
            val start = base + (boundedAt(data, base + i * WORD, data.size) ?: return null)
            val length = boundedAt(data, start, data.size) ?: return null
            if (start + WORD + length > data.size) return null
            data.copyOfRange(start + WORD, start + WORD + length)
        }
    }

    /** The word at [at] in [data] as an Int no larger than [most], or null when it is not one. */
    private fun boundedAt(data: ByteArray, at: Int, most: Int): Int? =
        wordAt(data, at)?.toLongOrNull()?.takeIf { it in 0..most.toLong() }?.toInt()

    /** The word at [at] in [data], or null when [data] ends before it does. */
    private fun wordAt(data: ByteArray, at: Int): UInt256? =
        if (at >= 0 && at + WORD <= data.size) AbiWords.uint(data.copyOfRange(at, at + WORD)) else null

    /**
     * The route and (amount in, minimum out) of an `exactInputSingle` or `exactInput` call; its
     * recipient is in the bytes [decode] compares.
     */
    private fun swapCall(call: ByteArray): Pair<SwapRoute, Pair<UInt256, UInt256>>? {
        if (call.size < SELECTOR_SIZE) return null
        val selector = call.copyOf(SELECTOR_SIZE)
        return when {
            selector.contentEquals(EXACT_INPUT_SINGLE) -> {
                val words = AbiWords.staticArguments(call, EXACT_INPUT_SINGLE, words = 7) ?: return null
                val tokenIn = AbiWords.address(words[0]) ?: return null
                val tokenOut = AbiWords.address(words[1]) ?: return null
                val fee = AbiWords.uint(words[2]).toLongOrNull()?.toInt()?.let(FeeTier::ofPips) ?: return null
                if (tokenIn == tokenOut) return null
                SwapRoute.direct(tokenIn, tokenOut, fee) to (AbiWords.uint(words[4]) to AbiWords.uint(words[5]))
            }
            selector.contentEquals(EXACT_INPUT) -> {
                val head = SELECTOR_SIZE + WORD
                val amountIn = wordAt(call, head + 2 * WORD) ?: return null
                val minimum = wordAt(call, head + 3 * WORD) ?: return null
                val pathAt = head + EXACT_INPUT_HEAD_WORDS * WORD
                val length = boundedAt(call, pathAt, call.size) ?: return null
                if (pathAt + WORD + length > call.size) return null
                val route = routeOf(call.copyOfRange(pathAt + WORD, pathAt + WORD + length)) ?: return null
                route to (amountIn to minimum)
            }
            else -> null
        }
    }

    /** The route a packed path spells (token ‖ fee ‖ token …), or null when it spells none. */
    private fun routeOf(path: ByteArray): SwapRoute? {
        if (path.size < ADDRESS_SIZE || (path.size - ADDRESS_SIZE) % (FEE_SIZE + ADDRESS_SIZE) != 0) return null
        val pools = (path.size - ADDRESS_SIZE) / (FEE_SIZE + ADDRESS_SIZE)
        val tokens = (0..pools).map { i ->
            val at = i * (FEE_SIZE + ADDRESS_SIZE)
            Address.of("0x" + path.copyOfRange(at, at + ADDRESS_SIZE).toHex())
        }
        val fees = (0 until pools).map { i ->
            val at = ADDRESS_SIZE + i * (FEE_SIZE + ADDRESS_SIZE)
            val pips = ((path[at].toInt() and 0xff) shl 16) or ((path[at + 1].toInt() and 0xff) shl 8) or
                (path[at + 2].toInt() and 0xff)
            FeeTier.ofPips(pips) ?: return null
        }
        return runCatching { SwapRoute(tokens, fees) }.getOrNull()
    }

    /**
     * What the swap returns, by what a [swap] call's run returned as [returnHex]: `multicall`'s
     * results, a `bytes[]` holding the swap's `amountOut` as one word, and when the account buys
     * ETH ([buysEther]) `unwrapWETH9`'s empty result after it. Null when it is not exactly that.
     */
    fun amountOutOf(returnHex: String, buysEther: Boolean): UInt256? {
        val data = AbiWords.returned(returnHex) ?: return null
        val calls = if (buysEther) 2 else 1
        // The array's offset and length, an offset per result, then the first result's length.
        val at = (2 + calls + 1) * WORD
        if (data.size < at + WORD) return null
        val amount = data.copyOfRange(at, at + WORD)
        val results = if (buysEther) listOf(amount, ByteArray(0)) else listOf(amount)
        val expected = AbiWords.withBytesArray(ByteArray(0), head = emptyList(), elements = results)
        return if (expected.contentEquals(data)) AbiWords.uint(amount) else null
    }

    private fun exactInputSingle(route: SwapRoute, recipient: Address, amountIn: UInt256, minimum: UInt256) =
        EXACT_INPUT_SINGLE +
            word(route.tokenIn) +
            word(route.tokenOut) +
            word(route.fees.single().pips.toLong()) +
            word(recipient) +
            amountIn.toBytes32() +
            minimum.toBytes32() +
            word(0)

    private fun exactInput(route: SwapRoute, recipient: Address, amountIn: UInt256, minimum: UInt256) =
        EXACT_INPUT +
            // The one argument is a dynamic tuple: its offset, then its head and the path.
            word(WORD.toLong()) +
            word((EXACT_INPUT_HEAD_WORDS * WORD).toLong()) +
            word(recipient) +
            amountIn.toBytes32() +
            minimum.toBytes32() +
            dynamic(route.path())

    private fun unwrapWeth9(minimum: UInt256, recipient: Address) = UNWRAP_WETH9 + minimum.toBytes32() + word(recipient)
}

/**
 * A swap decoded from a call of a router ([SwapRouter02.decode]): exactly [amountIn] along
 * [route] for at least [minimumOut], refused by [router] after [deadline] (seconds since the
 * epoch). [sellsEther]: the call's value is [amountIn], which the router wraps into the route's
 * first token; [buysEther]: the router unwraps the route's last token and sends it on as ETH.
 * Otherwise each end is the route's token itself.
 */
data class RouterSwap(
    val router: Address,
    val route: SwapRoute,
    val sellsEther: Boolean,
    val amountIn: UInt256,
    val buysEther: Boolean,
    val minimumOut: UInt256,
    val deadline: Long,
)

/** The token a route takes for this asset on [deployment]: the token's own, or WETH9 for ETH. */
fun TransferAsset.tokenOn(deployment: UniswapV3Deployment): Address = when (this) {
    TransferAsset.Native -> deployment.weth9
    is TransferAsset.Erc20Token -> token.address
}

/**
 * Whether selling this asset for [other] on [deployment] is a swap at all: their tokens differ.
 * The same asset twice is not, and neither is ETH against the WETH9 token, which is a wrap. Only
 * a pair that is one has pools to read or a route ([SwapRoutes]).
 */
fun TransferAsset.swapsWith(other: TransferAsset, deployment: UniswapV3Deployment): Boolean =
    tokenOn(deployment) != other.tokenOn(deployment)
