package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.hash.sha3.Keccak256

/** BIP-44 `m/44'/60'/0'/0/0`: purpose 44, coin type 60 (Ether), account 0, external chain, index 0. */
internal val FIRST_ETHEREUM_ACCOUNT = intArrayOf(
    44 or Bip32.HARDENED,
    60 or Bip32.HARDENED,
    0 or Bip32.HARDENED,
    0,
    0,
)

/** The Ethereum address of [privateKey]: the last 20 bytes of Keccak-256 over the public key's X || Y. */
internal fun addressOf(privateKey: ByteArray): Address {
    val publicKey = Secp256k1.pubkeyCreate(privateKey) // 0x04 || X || Y
    val hash = Keccak256().digest(publicKey.copyOfRange(1, 65))
    return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
}

/** The first account's address of a BIP-39 [seed]; the derived key is zeroed before returning. */
internal fun firstAddressOf(seed: ByteArray): Address {
    val key = Bip32.derive(seed, FIRST_ETHEREUM_ACCOUNT)
    try {
        return addressOf(key)
    } finally {
        key.fill(0)
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

/** The bytes [this] hex string spells; throws on anything else. */
internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
