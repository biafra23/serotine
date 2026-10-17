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

    private const val WORD_HEX_DIGITS = 64
    private const val HEX = "0123456789abcdef"
}
