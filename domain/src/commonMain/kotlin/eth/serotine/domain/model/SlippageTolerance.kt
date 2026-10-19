package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * How far below a verified quote a swap may return and still go through (docs/swaps.md,
 * *What both venues share*), in [basisPoints] (hundredths of a percent): the signed minimum is
 * the quote less this, rounded down ([minimumOf]). 0.5 % by default, and between 0.1 % and 5 %
 * (owner ruling, 2026-10-19, #69's decision 2); no second confirmation for a large price impact,
 * for now.
 *
 * The tolerance is what a sandwich can take of an account's swap quoted moments before it is
 * sent; the minimum itself is the pool's own quote less it, never a price.
 */
@JvmInline
value class SlippageTolerance private constructor(val basisPoints: Int) {

    /**
     * The least a swap quoted at [quote] may return: `quote × (10 000 − basisPoints) / 10 000`,
     * rounded down, exactly (no intermediate product that could overflow). Zero for a quote too
     * small to keep anything at this tolerance, which no swap is signed with
     * ([SwapRouter02.swap]).
     */
    fun minimumOf(quote: UInt256): UInt256 {
        val kept = WHOLE - basisPoints
        val (whole, rest) = quote.divRem(WHOLE)
        // whole × kept ≤ quote, and rest × kept < 10 000², so neither leaves its type.
        return whole * UInt256.of(kept.toLong()) + UInt256.of(rest.toLong() * kept / WHOLE)
    }

    /** The tolerance as a percentage with two decimals, as the user types it ("0.5" is 50). */
    val percent: String
        get() = UInt256.of(basisPoints.toLong()).formatUnits(PERCENT_DECIMALS)

    override fun toString(): String = "$percent %"

    companion object {
        /** Basis points in a whole. */
        private const val WHOLE = 10_000

        /** A percentage typed with up to two decimals is a whole number of basis points. */
        private const val PERCENT_DECIMALS = 2

        /** The least tolerance a swap may have: 0.1 %. */
        const val MIN_BASIS_POINTS = 10

        /** The most tolerance a swap may have: 5 %. */
        const val MAX_BASIS_POINTS = 500

        /** The tolerance a swap starts with: 0.5 %. */
        val DEFAULT: SlippageTolerance = SlippageTolerance(50)

        /** The tolerance of [basisPoints], or null outside [MIN_BASIS_POINTS]..[MAX_BASIS_POINTS]. */
        fun ofBasisPoints(basisPoints: Int): SlippageTolerance? =
            basisPoints.takeIf { it in MIN_BASIS_POINTS..MAX_BASIS_POINTS }?.let(::SlippageTolerance)

        /**
         * The tolerance [text] spells as a percentage ("0.5", "1", "0.25": at most two decimals,
         * as [UInt256.parseUnits] reads them), or null when it is no such number or out of bounds.
         */
        fun parsePercent(text: String): SlippageTolerance? =
            UInt256.parseUnits(text, PERCENT_DECIMALS)?.toLongOrNull()
                ?.takeIf { it <= MAX_BASIS_POINTS }
                ?.let { ofBasisPoints(it.toInt()) }
    }
}
