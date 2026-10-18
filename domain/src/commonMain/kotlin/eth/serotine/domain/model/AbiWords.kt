package eth.serotine.domain.model

/**
 * The few pieces of the contract ABI the domain encodes and decodes itself: 32-byte words, and
 * the strict reading of a call's arguments back out of its data. A word that is not what its
 * type allows (an address with bits above its 20 bytes, a number above what the caller takes)
 * is no argument of that type: decoding says null, never a value it guessed.
 */
internal object AbiWords {
    /** The size of an ABI word. */
    const val WORD = 32

    /** The size of a function selector. */
    const val SELECTOR = 4

    private const val ADDRESS_PADDING = WORD - 20

    /** [address] as a word: twelve zero bytes, then its twenty. */
    fun word(address: Address): ByteArray = ByteArray(ADDRESS_PADDING) + address.toBytes()

    /** [value], which is not negative, as a word. */
    fun word(value: Long): ByteArray = UInt256.of(value).toBytes32()

    /**
     * [bytes] as the tail of a dynamic `bytes` argument: its length as a word, then the bytes,
     * zero-padded on the right to a whole number of words.
     */
    fun dynamic(bytes: ByteArray): ByteArray = word(bytes.size.toLong()) + bytes + ByteArray(padding(bytes.size))

    /** The zero bytes that pad [size] bytes to a whole number of words. */
    fun padding(size: Int): Int = (WORD - size % WORD) % WORD

    /** The first four bytes of keccak256 of [signature] ("transfer(address,uint256)"). */
    fun selector(signature: String): ByteArray = keccak256(signature.encodeToByteArray()).copyOf(SELECTOR)

    /**
     * The arguments of a call to [selector] whose [data] is that selector and exactly [words]
     * static words, as the words; null when [data] is anything else.
     */
    fun staticArguments(data: ByteArray, selector: ByteArray, words: Int): List<ByteArray>? {
        if (data.size != SELECTOR + words * WORD) return null
        if (!data.copyOf(SELECTOR).contentEquals(selector)) return null
        return List(words) { data.copyOfRange(SELECTOR + it * WORD, SELECTOR + (it + 1) * WORD) }
    }

    /** The address [word] holds, or null when its upper twelve bytes are not zero. */
    fun address(word: ByteArray): Address? {
        if (word.size != WORD || (0 until ADDRESS_PADDING).any { word[it] != 0.toByte() }) return null
        return Address.of("0x" + word.copyOfRange(ADDRESS_PADDING, WORD).toHex())
    }

    /** The number [word] holds. */
    fun uint(word: ByteArray): UInt256 = UInt256.fromBigEndian(word)
}
