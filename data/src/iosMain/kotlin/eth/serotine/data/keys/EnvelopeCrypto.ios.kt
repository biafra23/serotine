package eth.serotine.data.keys

/**
 * Not yet on iOS: its envelope (the Secure Enclave, libsodium through cinterop, CryptoKit's
 * AES-GCM) comes with the iOS host, part 3 of feature/persistence. Until then the key store
 * reports itself unavailable there and stores nothing.
 */
internal actual object EnvelopeCrypto {
    actual fun available(): Boolean = false

    actual fun randomBytes(size: Int): ByteArray = unsupported()

    actual fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        outLength: Int,
    ): ByteArray = unsupported()

    actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        unsupported()

    actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? =
        unsupported()

    actual fun writeDurably(path: String, bytes: ByteArray): Unit = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("the key envelope is not on iOS yet")
}
