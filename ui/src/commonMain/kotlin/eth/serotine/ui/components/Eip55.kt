package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * EIP-55, the mixed-case checksum of an address: how every address is shown (docs/wallet-ux.md,
 * *Rules every screen follows*), and how one the user types is checked — the input layer
 * [Address]'s KDoc leaves that to. Presentation, so it lives in :ui.
 */
object Eip55 {

    /** [address] in its checksummed form, `0x` and 40 hex digits whose case carries the checksum. */
    fun format(address: Address): String {
        val hex = address.hex.removePrefix("0x")
        val hash = Keccak256().digest(hex.encodeToByteArray())
        return buildString(2 + hex.length) {
            append("0x")
            hex.forEachIndexed { i, digit ->
                val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0f
                append(if (digit in 'a'..'f' && nibble >= 8) digit.uppercaseChar() else digit)
            }
        }
    }

    /**
     * [text], as typed or pasted, read as an address: surrounding whitespace ignored, a
     * single-case address accepted as it is (it carries no checksum), a mixed-case one only
     * when its checksum holds.
     */
    fun parse(text: String): AddressInput {
        // An all-caps copy upper-cases the prefix too.
        val trimmed = text.trim().let { if (it.startsWith("0X")) "0x" + it.drop(2) else it }
        val address = Address.parse(trimmed) ?: return AddressInput.NotAnAddress
        val digits = trimmed.drop(2)
        val singleCase = digits == digits.lowercase() || digits == digits.uppercase()
        return when {
            singleCase -> AddressInput.Valid(address)
            trimmed == format(address) -> AddressInput.Valid(address)
            else -> AddressInput.ChecksumMismatch
        }
    }
}

/** What [Eip55.parse] makes of a text. */
sealed interface AddressInput {
    /** It is [address]. */
    data class Valid(val address: Address) : AddressInput

    /** Not `0x` and 40 hex digits. */
    data object NotAnAddress : AddressInput

    /** Mixed case whose EIP-55 checksum fails: a typo, refused before it becomes an address. */
    data object ChecksumMismatch : AddressInput
}

/** `0x1234…abcd`: an address shortened for a row; the full one is shown wherever the user confirms it. */
fun shortAddress(address: String): String = address.take(6) + "…" + address.takeLast(4)
