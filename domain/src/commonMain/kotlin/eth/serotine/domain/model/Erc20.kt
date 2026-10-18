package eth.serotine.domain.model

/** The ERC-20 call data this wallet builds and reads: the ABI encoding of the token contract's `transfer`. */
object Erc20 {
    /** The first four bytes of keccak256("transfer(address,uint256)"). */
    private val TRANSFER_SELECTOR = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())

    /** The size of an ABI word. */
    private const val WORD = 32

    /**
     * The call data of `transfer(to, amount)`: the selector `a9059cbb`, then [to] left-padded with
     * zeros to a 32-byte word, then [amount] (in the token's smallest units) as a 32-byte word —
     * 68 bytes, for a transaction to the token's contract that carries no ether.
     */
    fun transferData(to: Address, amount: UInt256): ByteArray {
        val address = to.toBytes()
        return TRANSFER_SELECTOR + ByteArray(WORD - address.size) + address + amount.toBytes32()
    }

    /**
     * What [data] transfers when it is exactly the call data of `transfer(to, amount)`, as
     * [transferData] builds it: the selector and two words, the first an address. Null for
     * anything else, a longer or shorter one included: only what is unambiguously a transfer
     * is read as one.
     */
    fun transferOf(data: ByteArray): Erc20Transfer? {
        val (to, amount) = AbiWords.staticArguments(data, TRANSFER_SELECTOR, words = 2) ?: return null
        return Erc20Transfer(AbiWords.address(to) ?: return null, AbiWords.uint(amount))
    }
}

/** An ERC-20 `transfer` decoded from call data: [amount] (in the token's smallest units) to [to]. */
data class Erc20Transfer(val to: Address, val amount: UInt256)
