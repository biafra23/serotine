package eth.serotine.domain.model

/**
 * Recursive Length Prefix, Ethereum's serialization (the yellow paper, appendix B), in the one
 * direction a wallet needs: encoding. An item is a byte string or a list of items; a list is
 * built from items already encoded, so nesting is the caller's composition of [encodeList]s.
 * Integers are byte strings: their minimal big-endian bytes, zero the empty string.
 */
internal object Rlp {
    private const val STRING_OFFSET = 0x80
    private const val LIST_OFFSET = 0xc0

    /** The longest payload whose length fits in the prefix byte itself. */
    private const val SHORT_MAX = 55

    /**
     * [bytes] as an RLP string: a single byte below 0x80 is its own encoding; anything else is
     * prefixed with its length — 0x80 + length up to 55 bytes, beyond that 0xb7 + the length's
     * own byte count, then the length, big-endian.
     */
    fun encodeString(bytes: ByteArray): ByteArray =
        if (bytes.size == 1 && (bytes[0].toInt() and 0xff) < STRING_OFFSET) {
            bytes.copyOf()
        } else {
            prefix(bytes.size, STRING_OFFSET) + bytes
        }

    /**
     * The RLP list of [items], each already RLP-encoded: their concatenation, prefixed with its
     * length as [encodeString] prefixes a string's, from 0xc0 instead of 0x80.
     */
    fun encodeList(items: List<ByteArray>): ByteArray {
        val payload = ByteArray(items.sumOf { it.size })
        var offset = 0
        for (item in items) {
            item.copyInto(payload, offset)
            offset += item.size
        }
        return prefix(payload.size, LIST_OFFSET) + payload
    }

    /** [value] as an RLP integer: its minimal big-endian bytes as a string, so zero is 0x80. */
    fun encodeUInt(value: UInt256): ByteArray = encodeString(value.toBigEndian())

    /** [value], which is not negative, as an RLP integer. */
    fun encodeUInt(value: Long): ByteArray = encodeUInt(UInt256.of(value))

    private fun prefix(length: Int, offset: Int): ByteArray {
        if (length <= SHORT_MAX) return byteArrayOf((offset + length).toByte())
        val lengthBytes = UInt256.of(length.toLong()).toBigEndian()
        return byteArrayOf((offset + SHORT_MAX + lengthBytes.size).toByte()) + lengthBytes
    }
}
