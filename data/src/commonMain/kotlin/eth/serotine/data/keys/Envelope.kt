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
 * The salt and the Argon2id parameters are bound as the DEK wrap's additional data and the
 * floor is checked again when opening (rule 2), so a parameter block edited downwards is
 * refused, not honoured. Every secret intermediate is zeroed before it is dropped.
 */
internal object Envelope {

    const val VERSION = 1

    /** The Argon2id floor (rule 2): never below 64 MiB, never fewer than two passes. */
    const val MIN_MEMORY_BYTES = 64L * 1024 * 1024
    const val MIN_OPS = 2L

    /** Argon2id parameters, as chosen for a device. */
    class Cost(val opsLimit: Long, val memLimitBytes: Long) {
        init {
            require(opsLimit >= MIN_OPS && memLimitBytes >= MIN_MEMORY_BYTES) { "below the Argon2id floor" }
        }
    }

    fun seal(seed: ByteArray, address: String, password: ByteArray, hardware: HardwareSecret?, cost: Cost): SealedSeed {
        val salt = EnvelopeCrypto.randomBytes(16)
        val hardwareTag = hardware?.tag ?: NO_HARDWARE
        val kek = kek(password, salt, cost.opsLimit, cost.memLimitBytes, hardware)
        val dek = EnvelopeCrypto.randomBytes(32)
        try {
            val dekNonce = EnvelopeCrypto.randomBytes(12)
            val seedNonce = EnvelopeCrypto.randomBytes(12)
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
     * The seed, or [Opened.WrongPassword] when the KEK does not open the DEK, or
     * [Opened.Refused] for an envelope this code must not open (another version, a
     * parameter block below the floor, another hardware secret, damaged data).
     */
    fun open(sealed: SealedSeed, password: ByteArray, hardware: HardwareSecret?): Opened {
        if (sealed.version != VERSION) return Opened.Refused
        if (sealed.opsLimit < MIN_OPS || sealed.memLimitBytes < MIN_MEMORY_BYTES) return Opened.Refused
        if (sealed.hardware != (hardware?.tag ?: NO_HARDWARE)) return Opened.Refused
        val salt = try {
            sealed.salt.fromHex()
        } catch (e: IllegalArgumentException) {
            return Opened.Refused
        }
        val kek = kek(password, salt, sealed.opsLimit, sealed.memLimitBytes, hardware)
        try {
            val dek = EnvelopeCrypto.aesGcmOpen(
                kek,
                sealed.dekNonce.fromHex(),
                sealed.dek.fromHex(),
                dekAad(sealed.address, sealed.hardware, salt, sealed.opsLimit, sealed.memLimitBytes),
            ) ?: return Opened.WrongPassword
            try {
                val seed = EnvelopeCrypto.aesGcmOpen(
                    dek,
                    sealed.seedNonce.fromHex(),
                    sealed.seed.fromHex(),
                    seedAad(sealed.address),
                )
                    ?: return Opened.Refused
                return Opened.Seed(seed)
            } finally {
                dek.fill(0)
            }
        } catch (e: IllegalArgumentException) {
            return Opened.Refused
        } finally {
            kek.fill(0)
        }
    }

    sealed interface Opened {
        /** The seed; the caller zeroes it. */
        class Seed(val bytes: ByteArray) : Opened
        data object WrongPassword : Opened
        data object Refused : Opened
    }

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
