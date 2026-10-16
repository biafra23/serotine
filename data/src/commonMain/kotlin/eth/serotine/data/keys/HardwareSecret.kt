package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection

/**
 * The envelope's hardware factor (docs/keys-and-signing.md, *The envelope*, `S_hw`): a
 * secret only this device's key store yields, mixed into the key-encryption key next to
 * the stretched password, so a copied envelope cannot be opened, or brute-forced, off the
 * device. On Android it is an HMAC computed inside the Keystore; on a Mac, a random secret
 * the Secure Enclave unwraps; a computer without a hardware key store has none, and its
 * envelope stands on the password alone.
 */
interface HardwareSecret {

    /** Names this secret in an envelope, so it is only ever opened with the same one. */
    val tag: String

    /** Whether the secret really lives in secure hardware, as the platform reports it. */
    val protection: KeyProtection

    /**
     * Whether every password guess needs this device: true when [mix] runs inside the hardware
     * for each input (Android's Keystore HMAC), false when it hands back a stored secret the
     * hardware unwraps (the Secure Enclave, which has no HMAC). Code that compromises the app
     * can unwrap such a secret once and take the search elsewhere, so Argon2id carries the load
     * (docs/keys-and-signing.md, rule 2).
     */
    val everyGuessNeedsDevice: Boolean

    /**
     * Whether this device's hardware key, the one [mix] uses, exists already, answered without
     * creating it. False before a wallet's first seal, and when the key was lost since: the key
     * store tells the two apart by whether envelopes with this [tag] exist, and refuses to let
     * [mix] make a new key beside them (their secret is gone, and a new one would only answer
     * "wrong password").
     */
    fun isProvisioned(): Boolean

    /** The secret for [input] (the stretched password), on this device only: the same input, the same output. */
    fun mix(input: ByteArray): ByteArray
}
