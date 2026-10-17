package eth.serotine.domain.model

/**
 * An EIP-1559 (type 2) transaction, unsigned: on the chain [chainId] names ([Network.chainId]),
 * the sender's transaction number [nonce] pays [value] wei to [to] with [data] as its call data,
 * using at most [gasLimit] gas at no more than [maxFeePerGas] wei per gas, of which at most
 * [maxPriorityFeePerGas] goes to the block's proposer. Its access list is always empty, and it
 * never creates a contract: [to] is always an address.
 *
 * Signing has the key adapter in the middle: the signer port signs [signingDigest];
 * [encodeSigned] puts that signature in and returns the raw transaction to broadcast, whose
 * transaction hash [hashOf] gives.
 *
 * What can be checked without the chain is checked at construction: a positive chain id, no
 * negative nonce or gas limit, a priority fee no higher than the max fee, and a fee cap
 * (`gasLimit × maxFeePerGas`) that fits in 256 bits. Equal by value, [data] included; not a data
 * class, because a data class compares an array by reference and hands out the array it holds.
 */
class Eip1559Transaction(
    val chainId: Long,
    val nonce: Long,
    val maxPriorityFeePerGas: UInt256,
    val maxFeePerGas: UInt256,
    val gasLimit: Long,
    val to: Address,
    val value: UInt256,
    data: ByteArray = ByteArray(0),
) {
    // Copied in, and copied again on the way out: the transaction stays as it was built.
    private val callData: ByteArray = data.copyOf()

    /** The call data, empty for a plain transfer of ether: a copy, so changing it changes nothing here. */
    val data: ByteArray
        get() = callData.copyOf()

    init {
        require(chainId > 0) { "a chain id is positive: $chainId" }
        require(nonce >= 0) { "a nonce is not negative: $nonce" }
        require(gasLimit >= 0) { "a gas limit is not negative: $gasLimit" }
        require(maxPriorityFeePerGas <= maxFeePerGas) {
            "the priority fee ($maxPriorityFeePerGas) exceeds the max fee ($maxFeePerGas)"
        }
        require(maxFeePerGas.timesOrNull(UInt256.of(gasLimit)) != null) {
            "gasLimit × maxFeePerGas exceeds 2^256 - 1"
        }
    }

    /**
     * The digest the sender signs, EIP-1559's signing hash: `keccak256(0x02 || rlp([chainId,
     * nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data, accessList]))`, every
     * integer minimal big-endian and the access list the empty list.
     */
    fun signingDigest(): Digest = Digest.of(keccak256(encodeUnsigned()).toHex())

    /**
     * The raw signed transaction, the bytes `eth_sendRawTransaction` takes: `0x02 || rlp([the nine
     * fields of signingDigest, yParity, r, s])`, with [signature]'s recovery id as `yParity` and its
     * r and s as integers (minimal big-endian, as RLP takes every integer).
     *
     * [signature] is to be the sender's signature of [signingDigest]. Without secp256k1 this cannot
     * check that, and a well-formed signature of anything else still encodes: as a transaction of
     * whatever address it recovers to. The key adapter that made the signature is where that is
     * known.
     */
    fun encodeSigned(signature: Signature): ByteArray = typed(
        fields() + listOf(
            Rlp.encodeUInt(signature.recoveryId.toLong()),
            Rlp.encodeUInt(UInt256.fromBigEndian(signature.r.hexToBytes())),
            Rlp.encodeUInt(UInt256.fromBigEndian(signature.s.hexToBytes())),
        ),
    )

    /** `0x02 || rlp([the nine fields])`: what [signingDigest] hashes. */
    internal fun encodeUnsigned(): ByteArray = typed(fields())

    private fun typed(items: List<ByteArray>): ByteArray = byteArrayOf(TYPE) + Rlp.encodeList(items)

    private fun fields(): List<ByteArray> = listOf(
        Rlp.encodeUInt(chainId),
        Rlp.encodeUInt(nonce),
        Rlp.encodeUInt(maxPriorityFeePerGas),
        Rlp.encodeUInt(maxFeePerGas),
        Rlp.encodeUInt(gasLimit),
        Rlp.encodeString(to.toBytes()),
        Rlp.encodeUInt(value),
        Rlp.encodeString(callData),
        Rlp.encodeList(emptyList()), // the access list
    )

    override fun equals(other: Any?): Boolean =
        other is Eip1559Transaction &&
            chainId == other.chainId &&
            nonce == other.nonce &&
            maxPriorityFeePerGas == other.maxPriorityFeePerGas &&
            maxFeePerGas == other.maxFeePerGas &&
            gasLimit == other.gasLimit &&
            to == other.to &&
            value == other.value &&
            callData.contentEquals(other.callData)

    override fun hashCode(): Int {
        var result = chainId.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + maxPriorityFeePerGas.hashCode()
        result = 31 * result + maxFeePerGas.hashCode()
        result = 31 * result + gasLimit.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + callData.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Eip1559Transaction(chainId=$chainId, nonce=$nonce, maxPriorityFeePerGas=$maxPriorityFeePerGas, " +
            "maxFeePerGas=$maxFeePerGas, gasLimit=$gasLimit, to=$to, value=$value, data=0x${callData.toHex()})"

    companion object {
        /** EIP-2718's type byte of an EIP-1559 transaction. */
        private const val TYPE: Byte = 0x02

        /**
         * The transaction hash of [raw], a signed transaction's bytes as [encodeSigned] returns
         * them: `0x` and the lower-case hex of keccak256([raw]) — what `eth_sendRawTransaction`
         * answers with, and what a block explorer finds the transaction by.
         */
        fun hashOf(raw: ByteArray): String = "0x" + keccak256(raw).toHex()

        /**
         * The least gas a transaction with call data [data] (to an address, no access list) must
         * carry before it runs at all: 21000, and for the data the larger of EIP-2028's price
         * (4 gas a zero byte, 16 a nonzero one) and EIP-7623's floor (10 per token, a zero byte
         * one token and a nonzero byte four), which since Prague is always the floor. A gas limit
         * below it is refused by every node.
         */
        fun intrinsicGas(data: ByteArray): Long {
            val zeros = data.count { it == 0.toByte() }.toLong()
            val nonzeros = data.size - zeros
            val standard = 4 * zeros + 16 * nonzeros
            val floor = 10 * (zeros + 4 * nonzeros)
            return BASE_GAS + maxOf(standard, floor)
        }

        /** What every transaction pays before its data and its execution. */
        private const val BASE_GAS = 21_000L
    }
}
