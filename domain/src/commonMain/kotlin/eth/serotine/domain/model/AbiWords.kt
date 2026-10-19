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

    /**
     * The number [word] holds when it fits in [bits] bits (a multiple of eight: a uint128, a
     * uint160), or null when a higher bit is set.
     */
    fun uint(word: ByteArray, bits: Int): UInt256? {
        require(bits % 8 == 0 && bits in 8..256) { "a uint of 8 to 256 bits, in whole bytes" }
        val zeros = WORD - bits / 8
        if (word.size != WORD || (0 until zeros).any { word[it] != 0.toByte() }) return null
        return UInt256.fromBigEndian(word)
    }

    /**
     * The signed number [word] holds when it is a [bits]-bit integer (a multiple of eight, at
     * most 32: an int24) sign-extended to the whole word, as the ABI encodes one; null otherwise.
     */
    fun int(word: ByteArray, bits: Int): Int? {
        require(bits % 8 == 0 && bits in 8..32) { "an int of 8 to 32 bits, in whole bytes" }
        if (word.size != WORD) return null
        val bytes = bits / 8
        val negative = word[WORD - bytes].toInt() < 0
        val extension = if (negative) 0xff.toByte() else 0
        if ((0 until WORD - bytes).any { word[it] != extension }) return null
        var value = if (negative) -1 else 0
        for (i in WORD - bytes until WORD) value = (value shl 8) or (word[i].toInt() and 0xff)
        return value
    }

    /** The number [word] holds when it fits in a non-negative [Long], or null. */
    fun long(word: ByteArray): Long? = uint(word, Long.SIZE_BITS)?.toLongOrNull()

    /**
     * The bytes a call returned, from [returnHex] (lower-case hex without `0x`, as a verified
     * run gives it), or null when [returnHex] is not that.
     */
    fun returned(returnHex: String): ByteArray? {
        if (returnHex.length % 2 != 0 || returnHex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return returnHex.hexToBytes()
    }

    /** The one word a call returned as [returnHex] (as [returned] reads it), or null when it returned anything else. */
    fun returnedWord(returnHex: String): ByteArray? = returned(returnHex)?.takeIf { it.size == WORD }

    /** [data] as exactly [words] words, or null when it is any other length. */
    fun words(data: ByteArray, words: Int): List<ByteArray>? {
        if (data.size != words * WORD) return null
        return List(words) { data.copyOfRange(it * WORD, (it + 1) * WORD) }
    }

    /**
     * The call data of [selector] with [elements] as one `bytes[]` argument after the static
     * [head] words: the head, the array's offset, then the array — its length, each element's
     * offset from the start of those offsets, and each element as a dynamic `bytes`.
     */
    fun withBytesArray(selector: ByteArray, head: List<ByteArray>, elements: List<ByteArray>): ByteArray {
        val arrayOffset = (head.size + 1) * WORD
        var offsets = ByteArray(0)
        var tails = ByteArray(0)
        for (element in elements) {
            offsets += word((elements.size * WORD + tails.size).toLong())
            tails += dynamic(element)
        }
        return head.fold(selector) { data, it -> data + it } +
            word(arrayOffset.toLong()) +
            word(elements.size.toLong()) +
            offsets +
            tails
    }
}
