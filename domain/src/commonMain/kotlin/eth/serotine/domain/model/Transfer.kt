package eth.serotine.domain.model

/** What a transfer moves: the network's own currency, or an ERC-20 [token]. */
sealed interface TransferAsset {

    /** The network's currency (ETH on Sepolia), sent as the transaction's value. */
    data object Native : TransferAsset

    /** [token], sent with its contract's `transfer`. */
    data class Erc20Token(val token: Token) : TransferAsset
}

/**
 * A signed transfer: its [raw] bytes as they are broadcast, and their [hash]. Not secret (a
 * broadcast makes it public), and kept when a broadcast fails, so that a retry sends exactly
 * these bytes and the receipt can be watched for by [hash] in case they reached a peer anyway.
 */
class SignedTransfer(raw: ByteArray) {
    private val bytes = raw.copyOf()

    /** The signed transaction's bytes: a copy. */
    val raw: ByteArray
        get() = bytes.copyOf()

    /** `0x` + keccak256 of [raw]: the transaction hash. */
    val hash: String = Eip1559Transaction.hashOf(bytes)

    override fun equals(other: Any?): Boolean = other is SignedTransfer && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "SignedTransfer($hash)"
}
