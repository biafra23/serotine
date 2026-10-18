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
     * The bytes a wallet password is stretched from: [text] in Unicode NFKC, as UTF-8. The
     * same password typed on two keyboards, one composing "ä" and one sending "a" plus a
     * combining diaeresis, must give the same bytes. The caller zeroes them.
     */
    fun passwordBytes(text: String): ByteArray

    /**
     * Argon2id (v1.3, one lane) of [password] with [salt], [opsLimit] passes over
     * [memLimitBytes] of memory, [outLength] bytes long. [password] is read as given:
     * every byte, whatever it encodes. [salt] is exactly [Envelope.SALT_BYTES] long. The
     * copies the native call needs are zeroed after it. Throws [Argon2idOutOfMemory] when
     * the memory cannot be had.
     */
    fun argon2id(password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, outLength: Int): ByteArray

    /** AES-256-GCM of [plaintext] under [key] and the 12-byte [nonce], authenticating [aad]; the tag is appended. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** The plaintext of [ciphertext], or null when the tag does not verify (wrong key, tampered data or [aad]). */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?

    /**
     * Creates the directory [path] if it is missing and makes it readable by this user
     * alone, where the file system has owner permissions.
     */
    fun createPrivateDirectory(path: String)

    /**
     * Writes [bytes] to [path] so that a crash or a power loss leaves either the old file or
     * the complete new one: a temporary file readable by this user alone, flushed to the
     * disk, then moved over [path], and the directory entry flushed where the platform
     * allows. The temporary file's name ends in [TEMPORARY_SUFFIX]. With [replace] false an
     * existing [path] is never replaced: the write fails instead.
     */
    fun writeDurably(path: String, bytes: ByteArray, replace: Boolean = true)
}

/** The name ending of [EnvelopeCrypto.writeDurably]'s temporary files; one left behind is a crashed write. */
internal const val TEMPORARY_SUFFIX = ".tmp"

/**
 * Argon2id could not have the memory its parameters ask for: the device is short of free memory
 * right now (the owner's ruling of 2026-10-18). Its own outcome, never a broken key store: sealing
 * tries a lighter tier ([PasswordOnlyTier]), and opening says to close other apps and try again.
 */
internal class Argon2idOutOfMemory : Exception("not enough memory for Argon2id")

/**
 * The password's stretching as [Envelope] runs it: [EnvelopeCrypto.argon2id], with the same
 * contract, unless a test hands it another (one that runs out of memory above a size).
 */
internal fun interface PasswordStretch {
    fun argon2id(password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, outLength: Int): ByteArray

    companion object {
        /** libsodium's Argon2id, through [EnvelopeCrypto]. */
        val Sodium = PasswordStretch(EnvelopeCrypto::argon2id)
    }
}
