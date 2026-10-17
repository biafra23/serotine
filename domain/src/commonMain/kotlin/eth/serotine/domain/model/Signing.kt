package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * A 32-byte hash to be signed — a transaction's signing hash, a Safe transaction's hash — as 64
 * lower-case hex digits, validated at construction. What it is the hash of is the caller's: a
 * [Digest] is signed as given, never hashed again.
 */
@JvmInline
value class Digest private constructor(val hex: String) {

    override fun toString(): String = "Digest($hex)"

    companion object {
        private val SHAPE = Regex("^[0-9a-f]{64}$")

        /** [hex] (with or without `0x`, any case) as a digest, or null when it is not 32 bytes of hex. */
        fun parse(hex: String): Digest? = hex.removePrefix("0x").lowercase().takeIf(SHAPE::matches)?.let(::Digest)

        /** [parse], throwing on bad input. */
        fun of(hex: String): Digest = requireNotNull(parse(hex)) { "not a 32-byte digest: '$hex'" }
    }
}

/**
 * A secp256k1 ECDSA signature: [r] and [s] as 64 lower-case hex digits each, [s] in the lower
 * half of the curve order (as Ethereum requires), and the [recoveryId] (0 or 1) that recovers the
 * signer's public key — EIP-1559's `yParity`, or a Safe signature's `v` less 27.
 */
data class Signature(val r: String, val s: String, val recoveryId: Int) {
    init {
        require(r.length == 64 && s.length == 64) { "r and s are 32 bytes each" }
        require(recoveryId in 0..1) { "a recovery id is 0 or 1" }
    }
}
