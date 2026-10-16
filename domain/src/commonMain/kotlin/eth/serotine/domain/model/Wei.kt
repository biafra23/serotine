package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * An amount of a network's native currency in wei, its smallest unit, held as the exact
 * non-negative decimal integer the chain stores: no floating point, no precision lost, no
 * upper bound. Construction validates the shape (decimal digits, no leading zeros).
 */
@JvmInline
value class Wei private constructor(val decimal: String) {

    override fun toString(): String = "$decimal wei"

    companion object {
        private val SHAPE = Regex("^(0|[1-9][0-9]*)$")

        /** The amount [decimal] spells, or null when it is not a non-negative decimal integer. */
        fun parse(decimal: String): Wei? = decimal.takeIf(SHAPE::matches)?.let(::Wei)

        /** [parse], throwing on bad input — for literals the caller knows are amounts. */
        fun of(decimal: String): Wei = requireNotNull(parse(decimal)) { "not a wei amount: '$decimal'" }
    }
}
