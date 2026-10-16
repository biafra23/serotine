package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The envelope's hardware factor on a Mac with a Secure Enclave, built as docs/keys-and-signing.md
 * builds it for iOS (*The envelope*): `HW_pw` is a P-256 key made inside the enclave, with no
 * user-authentication requirement, and `S_hw` is 32 random bytes encrypted to it with ECIES.
 * Both are kept in [stateFile]: the key as the enclave's own encrypted handle, which only this
 * Mac's enclave can use, and `S_hw` only in its encrypted form. [mix] has the enclave decrypt
 * `S_hw`, so a copied envelope, with or without [stateFile], is useless off this Mac.
 *
 * The enclave has no HMAC, so `S_hw` is the same for every guess
 * ([everyGuessNeedsDevice] is false): code that compromises the app can unwrap it once, and
 * Argon2id carries the load (rule 2). The app is not code-signed yet, so the handle is bound to
 * this Mac but not to the app: another program running as the same user could use it, as it
 * could read keystrokes. Signing adds that binding (the owner's ruling of 2026-10-16).
 */
class MacSecureEnclaveSecret(private val stateFile: String) : HardwareSecret {

    override val tag: String = TAG

    override val protection: KeyProtection = KeyProtection.HARDWARE

    override val everyGuessNeedsDevice: Boolean = false

    /** `S_hw`, whatever [input] is: decrypted by the enclave, or made and stored on first use. */
    @Synchronized
    override fun mix(input: ByteArray): ByteArray {
        val security = checkNotNull(MacSecurity.instance) { "Security.framework is not available" }
        return if (SystemFileSystem.exists(Path(stateFile))) unwrap(security) else create(security)
    }

    private fun unwrap(security: MacSecurity): ByteArray {
        val state = SystemFileSystem.source(Path(stateFile)).buffered().use { source ->
            JSON.decodeFromString(EnclaveState.serializer(), source.readString())
        }
        check(state.version == VERSION) { "unknown enclave state version ${state.version}" }
        val key = security.restoreEnclaveKey(state.key.fromHex())
        try {
            return security.eciesDecrypt(key, state.secret.fromHex()).also {
                check(it.size == SECRET_BYTES) { "the enclave's secret has the wrong length" }
            }
        } finally {
            security.release(key)
        }
    }

    private fun create(security: MacSecurity): ByteArray {
        val key = security.createEnclaveKey()
        val secret = EnvelopeCrypto.randomBytes(SECRET_BYTES)
        try {
            val state = EnclaveState(
                version = VERSION,
                key = security.tokenObjectIdOf(key).toHex(),
                secret = security.eciesEncrypt(key, secret).toHex(),
            )
            // Stored before the secret is used: an envelope sealed under it must be openable again.
            Path(stateFile).parent?.let { EnvelopeCrypto.createPrivateDirectory(it.toString()) }
            val json = JSON.encodeToString(EnclaveState.serializer(), state)
            EnvelopeCrypto.writeDurably(stateFile, json.encodeToByteArray())
            return secret
        } catch (e: Throwable) {
            secret.fill(0)
            throw e
        } finally {
            security.release(key)
        }
    }

    companion object {
        /** The envelopes' hardware tag for this secret. */
        const val TAG = "macos-secure-enclave-ecies-v1"

        /** The name of the state file next to the envelopes. */
        const val STATE_FILE_NAME = "secure-enclave.json"

        private const val VERSION = 1
        private const val SECRET_BYTES = 32
        private val JSON = Json

        /**
         * Whether this Mac's Secure Enclave makes and serves a key for this process now: an
         * ephemeral key is created, its handle restored and used once, and all of it dropped.
         * False off a Mac, on a Mac without an enclave, or when anything of it fails.
         */
        fun usable(): Boolean {
            val security = MacSecurity.instance ?: return false
            return try {
                val key = security.createEnclaveKey()
                try {
                    val restored = security.restoreEnclaveKey(security.tokenObjectIdOf(key))
                    try {
                        val probe = ByteArray(SECRET_BYTES) { it.toByte() }
                        security.eciesDecrypt(restored, security.eciesEncrypt(key, probe)).contentEquals(probe)
                    } finally {
                        security.release(restored)
                    }
                } finally {
                    security.release(key)
                }
            } catch (e: Exception) {
                println("Serotine: the Secure Enclave cannot be used (${e.message})")
                false
            }
        }
    }
}

/** [MacSecureEnclaveSecret]'s state file: the enclave key's handle and `S_hw` encrypted to it, both hex. */
@Serializable
internal class EnclaveState(val version: Int, val key: String, val secret: String)
