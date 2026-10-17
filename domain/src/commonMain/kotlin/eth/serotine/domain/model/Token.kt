package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * An ERC-20 token the wallet shows balances of: its contract [address], the [symbol],
 * [name] and [decimals] it is listed with, and the key of its bundled [icon] (null: drawn as
 * a monogram). For a built-in token those are pinned and were checked against the contract
 * when it was listed (CLAUDE.md, *Data sources*).
 */
data class Token(
    val address: Address,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val icon: String?,
) {
    init {
        require(symbol.isNotBlank()) { "a token needs a symbol" }
        // A uint256 has at most 78 decimal digits: more decimals than that cannot be shown.
        require(decimals in 0..MAX_DECIMALS) { "decimals out of range: $decimals" }
    }

    companion object {
        /** The most decimals a token can have and still be shown. */
        const val MAX_DECIMALS = 77
    }
}

/**
 * An amount of a token in its smallest unit, held as the exact non-negative decimal integer
 * the contract returns: no floating point, no precision lost. What its [Token.decimals]
 * make of it is the display's business.
 */
@JvmInline
value class TokenUnits private constructor(val decimal: String) {

    override fun toString(): String = "$decimal units"

    /** Whether the amount is zero. */
    val isZero: Boolean
        get() = decimal == "0"

    companion object {
        private val SHAPE = Regex("^(0|[1-9][0-9]*)$")

        /** The amount [decimal] spells, or null when it is not a non-negative decimal integer. */
        fun parse(decimal: String): TokenUnits? = decimal.takeIf(SHAPE::matches)?.let(::TokenUnits)

        /** [parse], throwing on bad input — for literals the caller knows are amounts. */
        fun of(decimal: String): TokenUnits = requireNotNull(parse(decimal)) { "not a token amount: '$decimal'" }
    }
}
