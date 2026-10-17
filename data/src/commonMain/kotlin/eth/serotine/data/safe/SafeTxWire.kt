package eth.serotine.data.safe

import eth.serotine.data.keys.fromHex
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import kotlinx.serialization.Serializable

/**
 * A [SigningRequest] as JSON: format version [v], the chain [chainId], the [safe], the raw
 * [tx] and the [signatures] collected so far. What [JsonSigningRequestCodec] sends between
 * devices, and what the queue file and the record of sent transactions keep of a request.
 * Every address and byte string is lower-case hex with `0x`, every uint256 a canonical decimal
 * string (no sign, no leading zero), so each request has one form only.
 */
@Serializable
internal class WireRequest(
    val v: Int,
    val chainId: Long,
    val safe: String,
    val tx: WireSafeTx,
    val signatures: List<WireSignature>,
) {
    /**
     * The request this spells on [network], or null when any field is out of its range: an
     * address or byte string not in its one form, a number not canonical or too large, an
     * operation other than 0 or 1, a signature that is not one (`v` other than 27 or 28, r or s
     * out of range, a high s), or two signatures of one owner. The chain is the caller's to
     * check. Never throws.
     */
    fun toRequest(network: Network): SigningRequest? {
        val safe = address(safe) ?: return null
        val tx = tx.toSafeTx() ?: return null
        val signatures = signatures.map { it.toSignature() ?: return null }
        if (signatures.map { it.owner }.toSet().size != signatures.size) return null
        return SigningRequest.of(network, safe, tx, signatures)
    }

    companion object {
        /**
         * The format version this build writes and reads. The queue file ([FileSafeTxQueue]) and
         * the record of sent transactions ([eth.serotine.data.transfers.FileSentTransfers]) keep
         * requests in this form too: a new version bumps their formats with it, or an older build
         * reads their files as damaged and sets them aside.
         */
        const val VERSION = 1

        /** [request] as its JSON object, its signatures in ascending owner order. */
        fun of(request: SigningRequest) = WireRequest(
            v = VERSION,
            chainId = request.network.chainId,
            safe = request.safe.hex,
            tx = WireSafeTx.of(request.tx),
            signatures = request.signatures.map(WireSignature::of),
        )
    }
}

/** A [SafeTx]'s fields, named as safe-smart-account names them. */
@Serializable
internal class WireSafeTx(
    val to: String,
    val value: String,
    val data: String,
    val operation: Int,
    val safeTxGas: String,
    val baseGas: String,
    val gasPrice: String,
    val gasToken: String,
    val refundReceiver: String,
    val nonce: String,
) {
    /** The transaction these fields spell, or null when one is out of its range (see [WireRequest.toRequest]). */
    fun toSafeTx(): SafeTx? {
        return SafeTx(
            to = address(to) ?: return null,
            value = UInt256.parse(value) ?: return null,
            data = bytes(data) ?: return null,
            operation = SafeOperation.of(operation) ?: return null,
            safeTxGas = UInt256.parse(safeTxGas) ?: return null,
            baseGas = UInt256.parse(baseGas) ?: return null,
            gasPrice = UInt256.parse(gasPrice) ?: return null,
            gasToken = address(gasToken) ?: return null,
            refundReceiver = address(refundReceiver) ?: return null,
            nonce = UInt256.parse(nonce)?.toLongOrNull() ?: return null,
        )
    }

    companion object {
        fun of(tx: SafeTx) = WireSafeTx(
            to = tx.to.hex,
            value = tx.value.toDecimal(),
            data = "0x" + tx.data.toHex(),
            operation = tx.operation.code,
            safeTxGas = tx.safeTxGas.toDecimal(),
            baseGas = tx.baseGas.toDecimal(),
            gasPrice = tx.gasPrice.toDecimal(),
            gasToken = tx.gasToken.hex,
            refundReceiver = tx.refundReceiver.hex,
            nonce = tx.nonce.toString(),
        )
    }
}

/** An owner's ECDSA signature of the request's hash: [signature] is `0x` and its 65 bytes, `r ‖ s ‖ v`. */
@Serializable
internal class WireSignature(val owner: String, val signature: String) {

    fun toSignature(): SafeSignature.Ecdsa? {
        val owner = address(owner) ?: return null
        if (!SIGNATURE_SHAPE.matches(signature)) return null
        val hex = signature.removePrefix("0x")
        val recoveryId = when (hex.substring(V_AT)) {
            "1b" -> 0
            "1c" -> 1
            else -> return null // 0 and 1 are no ECDSA signature's, 31 and 32 an eth_sign one's
        }
        val parsed = try {
            Signature(hex.substring(0, R_END), hex.substring(R_END, V_AT), recoveryId)
        } catch (e: IllegalArgumentException) {
            return null // r or s out of range, or a high s
        }
        return SafeSignature.Ecdsa(owner, parsed)
    }

    companion object {
        private const val R_END = 64
        private const val V_AT = 128

        fun of(signature: SafeSignature.Ecdsa) = WireSignature(
            owner = signature.owner.hex,
            signature = "0x" + signature.encode().toHex(),
        )
    }
}

private val ADDRESS_SHAPE = Regex("^0x[0-9a-f]{40}$")
private val SIGNATURE_SHAPE = Regex("^0x[0-9a-f]{130}$")

/** The address [text] is in its one form (lower-case, with `0x`), or null. */
private fun address(text: String): Address? = text.takeIf(ADDRESS_SHAPE::matches)?.let(Address::of)

/**
 * The bytes [text] is in its one form (lower-case hex, with `0x`), or null. Checked by hand, not
 * with a regex: a repeated group over a long call data would recurse once per byte.
 */
private fun bytes(text: String): ByteArray? {
    if (!text.startsWith("0x") || text.length % 2 != 0) return null
    if ((2 until text.length).any { text[it] !in '0'..'9' && text[it] !in 'a'..'f' }) return null
    return text.substring(2).fromHex()
}
