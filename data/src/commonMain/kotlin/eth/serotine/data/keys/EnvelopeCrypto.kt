package eth.serotine.data.keys

/**
 * The platform primitives the envelope is built from (docs/keys-and-signing.md, *The
 * envelope*): Argon2id from libsodium, AES-256-GCM and randomness from the platform, and a
 * write that reaches the disk before it replaces a file. Android and the desktop have them
 * (jvmSharedMain); iOS gets them with the iOS host, and until then [available] is false.
 */
internal expect object EnvelopeCrypto {

    /** Whether the primitives work on this device; false when libsodium cannot be loaded. */
    fun available(): Boolean

    /** [size] bytes from the platform's cryptographically secure generator. */
    fun randomBytes(size: Int): ByteArray

    /**
     * Argon2id (v1.3, one lane) of [password] with [salt], [opsLimit] passes over
     * [memLimitBytes] of memory, [outLength] bytes long. [password] is read as given:
     * every byte, whatever it encodes.
     */
    fun argon2id(password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, outLength: Int): ByteArray

    /** AES-256-GCM of [plaintext] under [key] and the 12-byte [nonce], authenticating [aad]; the tag is appended. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** The plaintext of [ciphertext], or null when the tag does not verify (wrong key, tampered data or [aad]). */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

    /**
     * Writes [bytes] to [path] so that a crash or a power loss leaves either the old file or
     * the complete new one: a temporary file, flushed to the disk, then moved over [path].
     */
    fun writeDurably(path: String, bytes: ByteArray)
}
