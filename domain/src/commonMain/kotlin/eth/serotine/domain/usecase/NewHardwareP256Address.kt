package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.NewHardwareP256Key
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * Generates a new P-256 hardware key and derives its Ethereum address.
 *
 * This use case is used when the user wants to add a P-256 hardware-bound owner to an
 * existing Safe{Wallet}. The key is generated in the device's Secure Enclave/StrongBox,
 * and the resulting address can be added as an owner to any Safe.
 *
 * The P-256 private key never leaves the hardware - signing for the Safe would require
 * a passkey signer contract at this address implementing EIP-1271.
 */
open class NewHardwareP256Address(
    private val keys: KeyStore,
) {
    /**
     * Generates a new P-256 hardware key and returns its Ethereum address.
     *
     * @return The outcome, either the generated address or failure reason
     */
    suspend operator fun invoke(): NewHardwareP256AddressResult =
        changes.serialized { generateAddress() }

    private suspend fun generateAddress(): NewHardwareP256AddressResult {
        println("NewHardwareP256Address: Generating P-256 hardware key...")
        val keyGeneration = keys.newHardwareP256Key()
        return when (keyGeneration) {
            is NewHardwareP256Key.Unavailable -> {
                println("NewHardwareP256Address: Key generation failed - Unavailable")
                NewHardwareP256AddressResult.KeyGenerationFailed
            }
            is NewHardwareP256Key.Generated -> {
                val address = deriveAddressFromPublicKey(keyGeneration)
                println("NewHardwareP256Address: Key generated, address: $address")
                NewHardwareP256AddressResult.Created(address)
            }
        }
    }

    /**
     * Derives an Ethereum address from a P-256 public key (x, y coordinates).
     *
     * The address is computed as keccak256(uncompressedPublicKey)[12:]
     * where uncompressedPublicKey starts with 0x04 followed by x and y.
     */
    private fun deriveAddressFromPublicKey(keyGeneration: NewHardwareP256Key.Generated): Address {
        // P-256 public key in uncompressed format: 0x04 || x || y
        val prefix = byteArrayOf(0x04.toByte())
        val xBytes = keyGeneration.x.hexToBytes()
        val yBytes = keyGeneration.y.hexToBytes()

        val publicKey = prefix + xBytes + yBytes
        val hash = keccak256(publicKey)

        return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
    }
}

/** The outcome of [NewHardwareP256Address]. */
sealed interface NewHardwareP256AddressResult {
    /**
     * A P-256 address was generated successfully.
     * @param address The derived Ethereum address
     */
    data class Created(val address: Address) : NewHardwareP256AddressResult

    /** The P-256 key could not be generated on this device. */
    data object KeyGenerationFailed : NewHardwareP256AddressResult
}

// Internal helper functions (copied from eth.serotine.domain.model.Bytes.kt)
// These are internal to that package but needed here for address derivation.

/** Keccak-256 of [bytes]: Ethereum's hash, the original Keccak padding rather than NIST SHA3-256's. */
private fun keccak256(bytes: ByteArray): ByteArray = Keccak256().digest(bytes)

/** The bytes this string of hex digits (either case, no prefix, even length) spells; throws on anything else. */
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> ((nibble(this[2 * i]) shl 4) or nibble(this[2 * i + 1])).toByte() }
}

private fun nibble(digit: Char): Int = when (digit) {
    in '0'..'9' -> digit - '0'
    in 'a'..'f' -> digit - 'a' + 10
    in 'A'..'F' -> digit - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$digit'")
}

/** These bytes as lower-case hex, two digits per byte, no prefix. */
private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
