package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * A 20-byte Ethereum account address, held as the lower-case `0x`-prefixed hex string it
 * is stored and compared as.
 *
 * Construction validates the SHAPE only (`0x` + 40 hex digits). EIP-55 checksum handling
 * belongs to the input layer that parses user-typed text: a mixed-case string with a wrong
 * checksum must be refused there, before it ever becomes an [Address]; an all-lower-case or
 * all-upper-case string carries no checksum and is accepted as it is.
 */
@JvmInline
value class Address private constructor(val hex: String) {

    override fun toString(): String = hex

    companion object {
        private val SHAPE = Regex("^0x[0-9a-fA-F]{40}$")

        /** The address [text] spells, or null when it is not one. Surrounding whitespace is ignored. */
        fun parse(text: String): Address? =
            text.trim().takeIf(SHAPE::matches)?.let { Address(it.lowercase()) }

        /** [parse], throwing on bad input — for literals the caller knows are addresses. */
        fun of(text: String): Address = requireNotNull(parse(text)) { "not an Ethereum address: '$text'" }
    }
}
