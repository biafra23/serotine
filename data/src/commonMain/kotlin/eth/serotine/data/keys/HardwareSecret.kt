package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection

/**
 * The envelope's hardware factor (docs/keys-and-signing.md, *The envelope*, `S_hw`): a
 * secret only this device's key store yields, mixed into the key-encryption key next to
 * the stretched password, so a copied envelope cannot be opened, or brute-forced, off the
 * device. On Android it is an HMAC computed inside the Keystore; a computer without a
 * hardware key store has none, and its envelope stands on the password alone.
 */
interface HardwareSecret {

    /** Names this secret in an envelope, so it is only ever opened with the same one. */
    val tag: String

    /** Whether the secret really lives in secure hardware, as the platform reports it. */
    val protection: KeyProtection

    /** The secret applied to [input] (the stretched password): the same input, the same output, on this device only. */
    fun mix(input: ByteArray): ByteArray
}
