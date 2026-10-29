package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.data.prices.PinnedPool
import eth.serotine.data.prices.UniswapPools
import eth.serotine.data.swaps.PinnedUniswapV3
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PastReading
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.PriceHistory
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.time.Duration

/**
 * [PriceFeeds] and [PriceHistory] for the price ids [pools] price, each a pinned Uniswap v3 pool's
 * time-weighted average over [UniswapPools.WINDOW_SECONDS], in WETH (CLAUDE.md, *Data sources*:
 * owner ruling, 2026-10-29, #205). Every read is a verified call at the verified head
 * ([VerifiedChain.call]), through [ProvenReads] at every head; no answer from anywhere else
 * stands in.
 *
 * A pool is a pool only when the pinned factory's `getPool` returns it in a verified call, as for
 * swaps (docs/swaps.md, *Pins*), and the factory only when its code, proven, hashes to its pin
 * ([uniswap]); both are checked once per adapter, since neither changes. Then, at each head, the
 * pool's average is no price ([NoPriceReason]) when any of these holds:
 * - it holds less WETH than its floor ([PinnedPool.minQuoteHeld]): [NoPriceReason.POOL_TOO_THIN];
 * - it keeps fewer than two observations, or its oldest is younger than the window: an idle
 *   pool's `observe` extrapolates its last tick, so its "average" would be its spot price
 *   ([NoPriceReason.POOL_HISTORY_SHORT]);
 * - its tick now (`slot0`) is more than [UniswapPools.MAX_TICK_DIVERGENCE] from the average:
 *   it was just moved ([NoPriceReason.POOL_MOVED]).
 * A reading is dated by the head it was read at and is never stale on its own; the feeds that
 * convert it (ETH/USD, a display currency's) carry their own age and staleness.
 *
 * The day back ([before]) is the same pool's average over the window ending that long before
 * the head, from its own observations at the head, never a call at a past block;
 * [PastReading.NoRoundThatOld] when its observations do not reach that far. Only the floor and the
 * divergence of the price now are not asked of it: they are not known for then.
 *
 * Each call is verified on its own, at the head it runs at; a head that moves between them moves
 * the window by a block, which an average over thirty minutes does not notice. A [network] other
 * than [served] is refused ([ReadUnavailable.NO_ENGINE]), and a price id with no pool pinned is
 * [NoPriceReason.NO_FEED]; so is every id on a network with no Uniswap pinned ([uniswap] null).
 */
class VerifiedPoolPrices internal constructor(
    private val served: Network,
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    pools: List<PinnedPool>,
    private val uniswap: PinnedUniswapV3?,
) : PriceFeeds, PriceHistory {

    private val pinned: Map<PriceId, PinnedPool> =
        if (uniswap == null) emptyMap() else pools.associateBy { it.priceId }

    private val servedQuotes: Map<PriceId, PriceId> = pinned.mapValues { it.value.quote }

    /** Whether the factory's proven code hashed to its pin: once true, for as long as this adapter lives. */
    @Volatile
    private var factoryProven = false

    /** The price ids whose pool the factory named: each checked once. Replaced whole on each write. */
    @Volatile
    private var poolsProven: Set<PriceId> = emptySet()

    override fun quotes(network: Network): Map<PriceId, PriceId> = if (network == served) servedQuotes else emptyMap()

    private val reads = ProvenReads<PriceId, FeedReading>(chain, dispatcher, pollInterval) { verified, id ->
        // The head's time before the calls: the calls block, and the head may move meanwhile.
        val headTime = (verified.snapshot() as? EngineSnapshot.Running)?.headTimestamp ?: return@ProvenReads null
        when (val read = average(verified, pinned.getValue(id), secondsBefore = 0, now = true)) {
            Read.Unproven -> null
            Read.Refused -> REFUSED
            is Read.NoPrice -> EngineReading.Proven(FeedReading.NoPrice(read.reason), read.block ?: UNKNOWN_BLOCK)
            is Read.Average -> EngineReading.Proven(
                FeedReading.Verified(read.price, headTime, ageSeconds = 0, stale = false, poolAverage = true),
                read.block ?: UNKNOWN_BLOCK,
                read.hash,
            )
        }
    }

    override fun observe(network: Network, priceId: PriceId): Flow<FeedReading> {
        if (network != served) return flowOf(FeedReading.Unavailable(ReadUnavailable.NO_ENGINE))
        if (priceId !in pinned) return flowOf(FeedReading.NoPrice(NoPriceReason.NO_FEED))
        return reads.observe(priceId).map { reading ->
            when (reading) {
                EngineReading.Syncing, is EngineReading.LastKnown -> FeedReading.Syncing
                is EngineReading.Proven -> reading.value
                is EngineReading.Unavailable -> FeedReading.Unavailable(reading.reason)
            }
        }.distinctUntilChanged()
    }

    override suspend fun before(network: Network, priceId: PriceId, secondsBefore: Long): PastReading {
        if (network != served) return PastReading.Unavailable(ReadUnavailable.NO_ENGINE)
        val pool = pinned[priceId] ?: return PastReading.NoPrice(NoPriceReason.NO_FEED)
        val chain = chain ?: return PastReading.Unavailable(ReadUnavailable.NO_ENGINE)
        return withContext(dispatcher) {
            try {
                past(chain, pool, secondsBefore)
            } catch (e: Throwable) {
                // As VerifiedPriceHistory: a cancellation ends the read; anything else is unproven.
                currentCoroutineContext().ensureActive()
                PastReading.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }

    private fun past(chain: VerifiedChain, pool: PinnedPool, secondsBefore: Long): PastReading {
        val snapshot = chain.snapshot()
        // As VerifiedPriceHistory: no engine, a failed start or a network that is off say so.
        if (snapshot !is EngineSnapshot.Running) {
            val why = whyUnreadable(snapshot)
            return if (why == null || why == ReadUnavailable.UNPROVEN) {
                PastReading.Syncing
            } else {
                PastReading.Unavailable(why)
            }
        }
        if (snapshot.phase == EngineSnapshot.Phase.STALE_ANCHOR) {
            return PastReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        }
        if (snapshot.readableHead == null) return PastReading.Syncing
        val headTime = snapshot.headTimestamp ?: return PastReading.Syncing
        return when (val read = average(chain, pool, secondsBefore, now = false)) {
            Read.Unproven -> PastReading.Unavailable(ReadUnavailable.UNPROVEN)
            Read.Refused -> PastReading.Unavailable(ReadUnavailable.REFUSED)
            is Read.NoPrice ->
                if (read.reason == NoPriceReason.POOL_HISTORY_SHORT) {
                    PastReading.NoRoundThatOld
                } else {
                    PastReading.NoPrice(read.reason)
                }
            is Read.Average -> PastReading.Verified(read.price, updatedAt = headTime - secondsBefore, stale = false)
        }
    }

    /** What one read of a pool came to. */
    private sealed interface Read {
        data object Unproven : Read
        data object Refused : Read
        data class NoPrice(val reason: NoPriceReason, val block: Long? = null) : Read

        /** One unit of the token cost [price] WETH over the window, read with the call at [block]. */
        data class Average(val price: Double, val block: Long?, val hash: String?) : Read
    }

    /**
     * [pool]'s average over the window ending [secondsBefore] before the head, with the checks of
     * the price [now] (the floor, the divergence) or without them, for a time gone by.
     */
    private fun average(verified: VerifiedChain, pool: PinnedPool, secondsBefore: Long, now: Boolean): Read {
        val uniswap = uniswap ?: return Read.NoPrice(NoPriceReason.NO_FEED)
        val deployment = uniswap.deployment
        if (!factoryProven) {
            val code = verified.proveCode(deployment.factory) ?: return Read.Unproven
            if (code.codeHex.isEmpty() || codeHash(code.codeHex) != uniswap.codeHashes[deployment.factory]) {
                return Read.NoPrice(NoPriceReason.NOT_A_POOL)
            }
            factoryProven = true
        }
        val weth = deployment.weth9
        val tokenFirst = pool.token.hex < weth.hex
        if (pool.priceId !in poolsProven) {
            val (first, second) = if (tokenFirst) pool.token to weth else weth to pool.token
            when (val named = verified.call(deployment.factory, getPool(first, second, pool.fee))) {
                null -> return Read.Unproven
                CallOutcome.Refused -> return Read.Refused
                is CallOutcome.Reverted -> return Read.NoPrice(NoPriceReason.NOT_A_POOL, named.blockNumber)
                is CallOutcome.Returned ->
                    if (Abi.address(named.hex) != pool.pool) {
                        return Read.NoPrice(NoPriceReason.NOT_A_POOL, named.blockNumber)
                    }
            }
            poolsProven = poolsProven + pool.priceId
        }

        if (now) {
            val held = when (val balance = verified.call(weth, Abi.balanceOf(pool.pool))) {
                null -> return Read.Unproven
                CallOutcome.Refused -> return Read.Refused
                is CallOutcome.Reverted -> return Read.NoPrice(NoPriceReason.NOT_A_POOL, balance.blockNumber)
                is CallOutcome.Returned ->
                    Abi.uint256(balance.hex)?.toDoubleOrNull() ?: return Read.NoPrice(NoPriceReason.NOT_A_POOL)
            }
            if (held / WETH_UNIT < pool.minQuoteHeld) return Read.NoPrice(NoPriceReason.POOL_TOO_THIN)
        }

        val slot0 = when (val answer = verified.call(pool.pool, SLOT0)) {
            null -> return Read.Unproven
            CallOutcome.Refused -> return Read.Refused
            is CallOutcome.Reverted -> return Read.NoPrice(NoPriceReason.NOT_A_POOL, answer.blockNumber)
            is CallOutcome.Returned -> Slot0.of(answer.hex) ?: return Read.NoPrice(NoPriceReason.NOT_A_POOL)
        }
        if (slot0.cardinality < 2) return Read.NoPrice(NoPriceReason.POOL_HISTORY_SHORT)

        // The oldest observation is the one after the latest in the ring, or the first while the
        // ring has not wrapped yet (that slot not written to so far).
        val next = (slot0.observationIndex + 1) % slot0.cardinality
        var oldest = observation(verified, pool, next) ?: return Read.Unproven
        if (oldest is Observed.Slot && !oldest.initialized) {
            oldest = observation(verified, pool, 0) ?: return Read.Unproven
        }
        when (oldest) {
            Observed.Refused -> return Read.Refused
            Observed.NotAPool -> return Read.NoPrice(NoPriceReason.NOT_A_POOL)
            is Observed.Slot -> {
                val headTime = (verified.snapshot() as? EngineSnapshot.Running)?.headTimestamp ?: return Read.Unproven
                if (!oldest.initialized || oldest.timestamp > headTime - secondsBefore - UniswapPools.WINDOW_SECONDS) {
                    return Read.NoPrice(NoPriceReason.POOL_HISTORY_SHORT)
                }
            }
        }

        val observed = verified.call(pool.pool, observe(secondsBefore + UniswapPools.WINDOW_SECONDS, secondsBefore))
        val cumulatives = when (observed) {
            null -> return Read.Unproven
            CallOutcome.Refused -> return Read.Refused
            // "OLD": the observations do not reach back that far after all.
            is CallOutcome.Reverted -> return Read.NoPrice(NoPriceReason.POOL_HISTORY_SHORT, observed.blockNumber)
            is CallOutcome.Returned -> tickCumulatives(observed.hex) ?: return Read.NoPrice(NoPriceReason.NOT_A_POOL)
        }
        val tick = (cumulatives.second - cumulatives.first).toDouble() / UniswapPools.WINDOW_SECONDS
        if (now && abs(tick - slot0.tick) > UniswapPools.MAX_TICK_DIVERGENCE) {
            return Read.NoPrice(NoPriceReason.POOL_MOVED, observed.blockNumber)
        }
        // A tick is the price of token0 in token1, in their smallest units: 1.0001^tick.
        val firstInSecond = 1.0001.pow(tick)
        val (firstDecimals, secondDecimals) =
            if (tokenFirst) pool.tokenDecimals to WETH_DECIMALS else WETH_DECIMALS to pool.tokenDecimals
        val scaled = firstInSecond * 10.0.pow(firstDecimals - secondDecimals)
        val price = if (tokenFirst) scaled else 1 / scaled
        if (!price.isFinite() || price <= 0) return Read.NoPrice(NoPriceReason.NOT_POSITIVE, observed.blockNumber)
        return Read.Average(price, observed.blockNumber, observed.blockHash)
    }

    private sealed interface Observed {
        data object Refused : Observed
        data object NotAPool : Observed
        data class Slot(val timestamp: Long, val initialized: Boolean) : Observed
    }

    /** The pool's observation at [index]; null when unproven. */
    private fun observation(verified: VerifiedChain, pool: PinnedPool, index: Int): Observed? =
        when (val answer = verified.call(pool.pool, observations(index))) {
            null -> null
            CallOutcome.Refused -> Observed.Refused
            is CallOutcome.Reverted -> Observed.NotAPool
            is CallOutcome.Returned -> {
                val words = answer.hex.chunked(WORD_HEX_DIGITS)
                val timestamp = words.getOrNull(0)?.let(Abi::uint256)?.toLongOrNull()
                val initialized = words.getOrNull(3)?.let(Abi::uint256)
                if (timestamp == null || initialized == null) {
                    Observed.NotAPool
                } else {
                    Observed.Slot(timestamp, initialized == "1")
                }
            }
        }

    /** What `slot0()` says that this adapter reads: the tick now, and the ring of observations. */
    private class Slot0(val tick: Long, val observationIndex: Int, val cardinality: Int) {
        companion object {
            fun of(hex: String): Slot0? {
                val words = hex.chunked(WORD_HEX_DIGITS).takeIf { it.size >= SLOT0_WORDS } ?: return null
                val tick = signed(words[1]) ?: return null
                val index = Abi.uint256(words[2])?.toIntOrNull() ?: return null
                val cardinality = Abi.uint256(words[3])?.toIntOrNull() ?: return null
                return Slot0(tick, index, cardinality).takeIf { cardinality == 0 || index < cardinality }
            }
        }
    }

    internal companion object {
        private val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)

        /** What [ProvenReads] is given for a call whose block the engine did not name. */
        private const val UNKNOWN_BLOCK = -1L

        private const val WETH_DECIMALS = 18
        private const val WETH_UNIT = 1e18
        private const val WORD_HEX_DIGITS = 64
        private const val SLOT0_WORDS = 7

        /** `slot0()`. */
        private val SLOT0: ByteArray = "3850c7bd".fromHex()

        /** `getPool(address,address,uint24)` of the factory. */
        fun getPool(first: Address, second: Address, fee: Int): ByteArray =
            ("1698ee82" + word(first) + fee.toLong().toString(16).padStart(WORD_HEX_DIGITS, '0')).fromHex()

        /** `observations(uint256)`. */
        fun observations(index: Int): ByteArray =
            ("252c09d7" + index.toLong().toString(16).padStart(WORD_HEX_DIGITS, '0')).fromHex()

        /** `observe(uint32[])` with the two ages [older] and [newer], in seconds before the call's block. */
        fun observe(older: Long, newer: Long): ByteArray = (
            "883bdbfd" + hexWord(0x20) + hexWord(2) + hexWord(older) + hexWord(newer)
            ).fromHex()

        private fun word(address: Address) = address.hex.removePrefix("0x").padStart(WORD_HEX_DIGITS, '0')

        private fun hexWord(n: Long) = n.toString(16).padStart(WORD_HEX_DIGITS, '0')

        /**
         * The two tick cumulatives `observe` returned for two ages: the first of its two arrays
         * (`int56[]`), found at the offset its head names. Null when it is not that shape.
         */
        fun tickCumulatives(hex: String): Pair<Long, Long>? {
            val words = hex.chunked(WORD_HEX_DIGITS)
            val offset = words.getOrNull(0)?.let(Abi::uint256)?.toIntOrNull() ?: return null
            if (offset % 32 != 0) return null
            val at = offset / 32
            if (words.getOrNull(at)?.let(Abi::uint256) != "2") return null
            val older = words.getOrNull(at + 1)?.let(::signed) ?: return null
            val newer = words.getOrNull(at + 2)?.let(::signed) ?: return null
            return older to newer
        }

        /**
         * A signed word that fits in 64 bits (an `int24` tick, an `int56` cumulative), sign-extended
         * to 256 as the ABI writes it; null for a word whose upper bits do not extend its sign.
         */
        fun signed(word: String): Long? {
            if (word.length != WORD_HEX_DIGITS) return null
            val low = word.takeLast(16).toULongOrNull(16)?.toLong() ?: return null
            val fill = if (low < 0) 'f' else '0'
            return low.takeIf { word.dropLast(16).all { it.lowercaseChar() == fill } }
        }
    }
}
