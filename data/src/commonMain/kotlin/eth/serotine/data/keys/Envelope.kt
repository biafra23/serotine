package eth.serotine.data.keys

import kotlinx.serialization.Serializable

/**
 * The envelope of docs/keys-and-signing.md around a BIP-39 seed:
 *
 * ```
 * DEK     random 32 bytes
 * seed'   AES-256-GCM(DEK, seed),            aad "serotine-seed-v1|<address>"
 * P       Argon2id(password, salt, ops, mem)
 * S_hw    hardware.mix(P)                     (absent without a hardware secret)
 * KEK     HKDF-SHA256(P || S_hw, salt, "serotine-envelope-v1 kek")
 * DEK'    AES-256-GCM(KEK, DEK),             aad = version, address, hardware tag, salt, ops, mem
 * ```
 *
 * Both factors are necessary (rule 1): the password is mixed into the KEK, never compared.
 * The salt and the Argon2id parameters are bound as the DEK wrap's additional data and
 * checked against a floor and a ceiling when opening (rule 2), so a parameter block edited
 * downwards is refused, not honoured, and one edited upwards cannot hang the device. Every
 * secret intermediate this code holds is zeroed before it is dropped.
 */
internal object Envelope {

    const val VERSION = 1

    /** The Argon2id floor (rule 2): never below 64 MiB, never fewer than two passes. */
    const val MIN_MEMORY_BYTES = 64L * 1024 * 1024
    const val MIN_OPS = 2L

    /**
     * The Argon2id ceiling: the costliest parameters this code writes (the password-only
     * ones). A stored block above it is damaged, and running it could hang the import or
     * exhaust a phone's memory, so it is refused.
     */
    const val MAX_MEMORY_BYTES = 256L * 1024 * 1024
    const val MAX_OPS = 4L

    /** libsodium's `crypto_pwhash_SALTBYTES`. */
    const val SALT_BYTES = 16

    private const val NONCE_BYTES = 12

    /** Argon2id parameters, as chosen for a device. */
    class Cost(val opsLimit: Long, val memLimitBytes: Long) {
        init {
            require(opsLimit in MIN_OPS..MAX_OPS && memLimitBytes in MIN_MEMORY_BYTES..MAX_MEMORY_BYTES) {
                "outside the Argon2id floor and ceiling"
            }
        }
    }

    fun seal(seed: ByteArray, address: String, password: ByteArray, hardware: HardwareSecret?, cost: Cost): SealedSeed {
        val salt = EnvelopeCrypto.randomBytes(SALT_BYTES)
        val hardwareTag = hardware?.tag ?: NO_HARDWARE
        val kek = kek(password, salt, cost.opsLimit, cost.memLimitBytes, hardware)
        val dek = EnvelopeCrypto.randomBytes(32)
        try {
            val dekNonce = EnvelopeCrypto.randomBytes(NONCE_BYTES)
            val seedNonce = EnvelopeCrypto.randomBytes(NONCE_BYTES)
            return SealedSeed(
                version = VERSION,
                address = address,
                hardware = hardwareTag,
                salt = salt.toHex(),
                opsLimit = cost.opsLimit,
                memLimitBytes = cost.memLimitBytes,
                dekNonce = dekNonce.toHex(),
                dek = EnvelopeCrypto.aesGcmSeal(
                    kek,
                    dekNonce,
                    dek,
                    dekAad(address, hardwareTag, salt, cost.opsLimit, cost.memLimitBytes),
                ).toHex(),
                seedNonce = seedNonce.toHex(),
                seed = EnvelopeCrypto.aesGcmSeal(dek, seedNonce, seed, seedAad(address)).toHex(),
            )
        } finally {
            kek.fill(0)
            dek.fill(0)
        }
    }

    /**
     * Whether [sealed] is an envelope this code may try to open with [hardware], judged
     * without the password: this version, this device's hardware tag, parameters within the
     * floor and the ceiling, a well-formed salt and nonces, and — when [address] is given —
     * that address, the one its file is named after.
     */
    fun canOpen(sealed: SealedSeed, hardware: HardwareSecret?, address: String? = null): Boolean =
        sealed.version == VERSION &&
            sealed.hardware == (hardware?.tag ?: NO_HARDWARE) &&
            sealed.opsLimit in MIN_OPS..MAX_OPS &&
            sealed.memLimitBytes in MIN_MEMORY_BYTES..MAX_MEMORY_BYTES &&
            (address == null || sealed.address == address) &&
            hexOfLength(sealed.salt, SALT_BYTES) &&
            hexOfLength(sealed.dekNonce, NONCE_BYTES) &&
            hexOfLength(sealed.seedNonce, NONCE_BYTES)

    /**
     * Whether [password] (with [hardware]) opens [sealed], checked by unwrapping its DEK and
     * nothing more: the seed stays sealed. [Opened.Refused] when [canOpen] says no or the
     * data is damaged.
     */
    fun verify(sealed: SealedSeed, password: ByteArray, hardware: HardwareSecret?): Opened {
        val dek = when (val unwrapped = unwrapDek(sealed, password, hardware)) {
            is Unwrapped.Dek -> unwrapped.bytes
            Unwrapped.WrongPassword -> return Opened.WrongPassword
            Unwrapped.Refused -> return Opened.Refused
        }
        dek.fill(0)
        return Opened.Verified
    }

    /**
     * The seed, or [Opened.WrongPassword] when the KEK does not open the DEK, or
     * [Opened.Refused] for an envelope this code must not open ([canOpen]) or whose data is
     * damaged.
     */
    fun open(sealed: SealedSeed, password: ByteArray, hardware: HardwareSecret?): Opened {
        val dek = when (val unwrapped = unwrapDek(sealed, password, hardware)) {
            is Unwrapped.Dek -> unwrapped.bytes
            Unwrapped.WrongPassword -> return Opened.WrongPassword
            Unwrapped.Refused -> return Opened.Refused
        }
        try {
            val seed = EnvelopeCrypto.aesGcmOpen(
                dek,
                sealed.seedNonce.fromHex(),
                sealed.seed.fromHex(),
                seedAad(sealed.address),
            ) ?: return Opened.Refused
            return Opened.Seed(seed)
        } catch (e: IllegalArgumentException) {
            return Opened.Refused
        } finally {
            dek.fill(0)
        }
    }

    sealed interface Opened {
        /** The seed; the caller zeroes it. */
        class Seed(val bytes: ByteArray) : Opened

        /** The password opens the envelope ([verify]). */
        data object Verified : Opened
        data object WrongPassword : Opened
        data object Refused : Opened
    }

    private sealed interface Unwrapped {
        class Dek(val bytes: ByteArray) : Unwrapped
        data object WrongPassword : Unwrapped
        data object Refused : Unwrapped
    }

    private fun unwrapDek(sealed: SealedSeed, password: ByteArray, hardware: HardwareSecret?): Unwrapped {
        if (!canOpen(sealed, hardware)) return Unwrapped.Refused
        val salt = sealed.salt.fromHex()
        val kek = kek(password, salt, sealed.opsLimit, sealed.memLimitBytes, hardware)
        try {
            val dek = EnvelopeCrypto.aesGcmOpen(
                kek,
                sealed.dekNonce.fromHex(),
                sealed.dek.fromHex(),
                dekAad(sealed.address, sealed.hardware, salt, sealed.opsLimit, sealed.memLimitBytes),
            ) ?: return Unwrapped.WrongPassword
            if (dek.size != 32) {
                dek.fill(0)
                return Unwrapped.Refused
            }
            return Unwrapped.Dek(dek)
        } catch (e: IllegalArgumentException) {
            return Unwrapped.Refused
        } finally {
            kek.fill(0)
        }
    }

    private fun hexOfLength(hex: String, bytes: Int): Boolean =
        hex.length == bytes * 2 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun kek(password: ByteArray, salt: ByteArray, ops: Long, mem: Long, hardware: HardwareSecret?): ByteArray {
        val p = EnvelopeCrypto.argon2id(password, salt, ops, mem, 32)
        val sHw = hardware?.mix(p)
        val ikm = if (sHw == null) p.copyOf() else p + sHw
        try {
            return Hkdf.sha256(ikm, salt, KEK_INFO, 32)
        } finally {
            p.fill(0)
            sHw?.fill(0)
            ikm.fill(0)
        }
    }

    private fun dekAad(address: String, hardware: String, salt: ByteArray, ops: Long, mem: Long): ByteArray =
        "serotine-envelope-v$VERSION|$address|$hardware|${salt.toHex()}|$ops|$mem".encodeToByteArray()

    private fun seedAad(address: String): ByteArray = "serotine-seed-v$VERSION|$address".encodeToByteArray()

    private const val NO_HARDWARE = "none"
    private val KEK_INFO = "serotine-envelope-v1 kek".encodeToByteArray()
}

/** An envelope as it is stored: everything hex, nothing secret in the clear. */
@Serializable
internal class SealedSeed(
    val version: Int,
    val address: String,
    val hardware: String,
    val salt: String,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val dekNonce: String,
    val dek: String,
    val seedNonce: String,
    val seed: String,
)
