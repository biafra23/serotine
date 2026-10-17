package eth.serotine.data.engine

import eth.serotine.data.keys.fromHex
import eth.serotine.domain.model.Address

/** The few pieces of the contract ABI the engine adapters speak: calldata in, words out. */
internal object Abi {

    /** `balanceOf(address)`: the selector and [holder] as one left-padded word. */
    fun balanceOf(holder: Address): ByteArray = ("70a08231" + "0".repeat(24) + holder.hex.removePrefix("0x")).fromHex()

    /**
     * The one uint256 word [returnedHex] (lower-case hex, no `0x`) holds, as a decimal integer;
     * null when it is not exactly one word, so a call that answered something else is not
     * taken for an amount.
     */
    fun uint256(returnedHex: String): String? {
        if (returnedHex.length != WORD_HEX_DIGITS || !returnedHex.all { it in HEX }) return null
        return toDecimal(returnedHex.fromHex())
    }

    /** `symbol()`, `name()`, `decimals()`: no arguments, the selector alone. */
    val SYMBOL: ByteArray = "95d89b41".fromHex()
    val NAME: ByteArray = "06fdde03".fromHex()
    val DECIMALS: ByteArray = "313ce567".fromHex()

    /**
     * The text [returnedHex] holds: an ABI `string` (its offset, its length, its bytes), or, as
     * some early tokens answer `symbol()` and `name()`, one `bytes32` word padded with zero
     * bytes. Null when it is neither, or not valid UTF-8; control characters are dropped, so
     * what a contract says can never move text on screen.
     */
    fun text(returnedHex: String): String? {
        if (returnedHex.length % 2 != 0 || !returnedHex.all { it in HEX }) return null
        val bytes = returnedHex.fromHex()
        val raw = when {
            bytes.size == WORD -> bytes.copyOf(bytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: WORD)
            bytes.size >= 2 * WORD -> {
                val offset = wordAsInt(bytes, 0) ?: return null
                val length = offset.takeIf { it + WORD <= bytes.size }?.let { wordAsInt(bytes, it) } ?: return null
                if (offset + WORD + length > bytes.size) return null
                bytes.copyOfRange(offset + WORD, offset + WORD + length)
            }
            else -> return null
        }
        val decoded = raw.decodeToString(throwOnInvalidSequence = false)
        if ('\uFFFD' in decoded) return null
        return decoded.filterNot { it.isISOControl() }
    }

    /** The word at [at] as a small non-negative Int, or null when it is larger than any offset or length can be. */
    private fun wordAsInt(bytes: ByteArray, at: Int): Int? {
        if (at + WORD > bytes.size) return null
        val high = bytes.copyOfRange(at, at + WORD - 4)
        if (high.any { it != 0.toByte() }) return null
        var value = 0L
        for (i in at + WORD - 4 until at + WORD) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return value.takeIf { it <= MAX_TEXT_BYTES }?.toInt()
    }

    /** The unsigned big-endian [bytes] as a decimal integer, by long division in base 10. */
    internal fun toDecimal(bytes: ByteArray): String {
        var number = bytes.map { it.toInt() and 0xff }.dropWhile { it == 0 }
        if (number.isEmpty()) return "0"
        val digits = StringBuilder()
        while (number.isNotEmpty()) {
            var remainder = 0
            val quotient = ArrayList<Int>(number.size)
            for (byte in number) {
                val value = remainder * 256 + byte
                quotient += value / 10
                remainder = value % 10
            }
            digits.append(('0' + remainder))
            number = quotient.dropWhile { it == 0 }
        }
        return digits.reverse().toString()
    }

    private const val WORD = 32
    private const val WORD_HEX_DIGITS = 64

    /** Longer than any symbol or name a screen shows: what a contract says beyond this is refused. */
    private const val MAX_TEXT_BYTES = 1_024L
    private const val HEX = "0123456789abcdef"
}
