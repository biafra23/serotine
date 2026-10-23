package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable

/**
 * The envelope's biometric path (docs/keys-and-signing.md, *The envelope*, `HW_bio`; rules 3 to
 * 5): a second hardware key, apart from the password path's, that wraps each envelope's DEK and
 * gives it back only after a per-use strong biometric in the system's prompt. Wrapping needs no
 * biometric, since it uses the key's public half; unwrapping always does, and the hardware, not
 * the UI, enforces it. Android's is an RSA key in the Keystore, iOS's and a Mac's a P-256 key in
 * the Secure Enclave; Windows and Linux have none yet, and their key store reports so.
 */
interface BiometricKey {

    /** Names this key's wraps, so a wrap is only ever opened by the kind of key that made it. */
    val tag: String

    /** Why this device cannot offer the path now, or null when it can: strong biometrics only (rule 3). */
    fun unavailable(): BiometricUnavailable?

    /** Whether the key exists, valid or not. */
    fun exists(): Boolean

    /** Whether the key exists but a change of the enrolled biometrics invalidated it (rule 3). */
    fun isInvalidated(): Boolean

    /**
     * Makes the key anew, deleting any before it. False, with no key left, when the platform
     * would not keep it in secure hardware (rule 5).
     */
    fun create(): Boolean

    /** [dek] wrapped by the key: no biometric needed. */
    fun wrap(dek: ByteArray): ByteArray

    /**
     * The DEK [wrapped] holds, once a strong biometric in the system's prompt, which says
     * [reason], allows the key one use. Blocks on the user; never throws.
     */
    suspend fun unwrap(wrapped: ByteArray, reason: String): BiometricUnwrap

    /** Deletes the key: nothing it wrapped can be opened by it any more. */
    fun delete()
}

/** The outcome of [BiometricKey.unwrap]. */
sealed interface BiometricUnwrap {

    /** The DEK; the caller zeroes it. */
    class Dek(val bytes: ByteArray) : BiometricUnwrap

    /** The prompt was dismissed. */
    data object Cancelled : BiometricUnwrap

    /** No biometric can open it now, for [reason]. */
    data class Unusable(val reason: BiometricUnusable) : BiometricUnwrap

    /** The key or the prompt failed, or the wrap is damaged. */
    data object Failed : BiometricUnwrap
}
