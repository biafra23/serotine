package eth.serotine.data.keys

import org.kotlincrypto.hash.sha2.SHA256

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/**
 * The chain code and private key (as hex) inside a serialized BIP-32 `xprv`, for comparing
 * derivations with the published vectors. Test-only: production code never parses one.
 */
internal fun decodeXprv(xprv: String): Pair<String, String> {
    val bytes = base58CheckDecode(xprv)
    // version(4) depth(1) parent fingerprint(4) child number(4) chain code(32) 0x00 key(32)
    check(bytes.size == 78) { "an xprv payload is 78 bytes, got ${bytes.size}" }
    check(bytes[45] == 0.toByte()) { "not a private key" }
    return bytes.copyOfRange(13, 45).toHex() to bytes.copyOfRange(46, 78).toHex()
}

private fun base58CheckDecode(text: String): ByteArray {
    val digits = mutableListOf<Int>() // base 256, least significant first
    for (c in text) {
        var carry = BASE58.indexOf(c)
        check(carry >= 0) { "not base58: '$c'" }
        for (i in digits.indices) {
            val x = digits[i] * 58 + carry
            digits[i] = x and 0xff
            carry = x shr 8
        }
        while (carry > 0) {
            digits += carry and 0xff
            carry = carry shr 8
        }
    }
    val zeros = text.takeWhile { it == '1' }.length
    val decoded = ByteArray(zeros) + digits.reversed().map { it.toByte() }.toByteArray()
    val payload = decoded.copyOfRange(0, decoded.size - 4)
    val checksum = SHA256().digest(SHA256().digest(payload)).copyOfRange(0, 4)
    check(checksum.contentEquals(decoded.copyOfRange(decoded.size - 4, decoded.size))) { "bad base58check checksum" }
    return payload
}

/** `m/0'/1/…` as child indices. */
internal fun parsePath(path: String): IntArray {
    val segments = path.split('/')
    check(segments.first() == "m") { "a path starts at m: $path" }
    return segments.drop(1).map { segment ->
        if (segment.endsWith("'")) segment.dropLast(1).toInt() or Bip32.HARDENED else segment.toInt()
    }.toIntArray()
}

internal fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
