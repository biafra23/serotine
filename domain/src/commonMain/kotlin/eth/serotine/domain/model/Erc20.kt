package eth.serotine.domain.model

/**
 * The ERC-20 call data this wallet builds and reads: the ABI encoding of the token contract's
 * `transfer`, and of the `approve` and `allowance` a swap's approval step needs (docs/swaps.md,
 * *What both venues share*: the exact amount, its own transaction; owner ruling, 2026-10-19, #69's
 * decision 3).
 */
object Erc20 {
    /** The first four bytes of keccak256("transfer(address,uint256)"). */
    private val TRANSFER_SELECTOR = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())

    private val APPROVE_SELECTOR = AbiWords.selector("approve(address,uint256)")
    private val ALLOWANCE_SELECTOR = AbiWords.selector("allowance(address,address)")

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

    /**
     * The call data of `approve(spender, amount)`: the selector `095ea7b3`, then [spender] and
     * [amount] (in the token's smallest units) as words. It sets the allowance to [amount],
     * whatever it was; a token that refuses to move one non-zero allowance to another (USDT's
     * kind) reverts it, which the approval's verified run shows, and the approval step then sets
     * it to zero first (docs/swaps.md, *What both venues share*).
     */
    fun approveData(spender: Address, amount: UInt256): ByteArray =
        APPROVE_SELECTOR + AbiWords.word(spender) + amount.toBytes32()

    /**
     * What [data] approves when it is exactly the call data of `approve(spender, amount)`, as
     * [approveData] builds it: the selector and two words, the first an address. Null for
     * anything else.
     */
    fun approveOf(data: ByteArray): Erc20Approval? {
        val (spender, amount) = AbiWords.staticArguments(data, APPROVE_SELECTOR, words = 2) ?: return null
        return Erc20Approval(AbiWords.address(spender) ?: return null, AbiWords.uint(amount))
    }

    /** The call data of `allowance(owner, spender)`: what [spender] may still take of [owner]'s tokens. */
    fun allowanceData(owner: Address, spender: Address): ByteArray =
        ALLOWANCE_SELECTOR + AbiWords.word(owner) + AbiWords.word(spender)

    /** The allowance `allowance` returned as [returnHex], or null when that is not one word. */
    fun allowanceOf(returnHex: String): UInt256? =
        AbiWords.returnedWord(returnHex)?.let(AbiWords::uint)
}

/** An ERC-20 `transfer` decoded from call data: [amount] (in the token's smallest units) to [to]. */
data class Erc20Transfer(val to: Address, val amount: UInt256)

/** An ERC-20 `approve` decoded from call data: [spender] may take [amount] (in the token's smallest units). */
data class Erc20Approval(val spender: Address, val amount: UInt256)
