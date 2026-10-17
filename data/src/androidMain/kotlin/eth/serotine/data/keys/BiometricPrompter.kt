package eth.serotine.data.keys

import javax.crypto.Cipher

/**
 * The system's biometric prompt, which the Android host shows from its Activity
 * (docs/keys-and-signing.md, rule 3): it authorizes one use of [AndroidBiometricKey]'s key,
 * through a `CryptoObject` that holds the [Cipher], so the biometric is bound to the key and
 * not merely to the screen.
 */
interface BiometricPrompter {

    /**
     * Shows the prompt, saying [reason], for one use of [cipher]: [PromptResult.Authenticated]
     * with the cipher the system unlocked, or why not. Suspends until the user answers; a
     * cancelled caller dismisses it. Never throws.
     */
    suspend fun authenticate(cipher: Cipher, reason: String): PromptResult
}

/** The outcome of [BiometricPrompter.authenticate]. */
sealed interface PromptResult {

    /** A strong biometric matched: [cipher] may be used once. */
    class Authenticated(val cipher: Cipher) : PromptResult

    /** The user dismissed the prompt, or chose the password instead. */
    data object Cancelled : PromptResult

    /** Too many failed attempts: the system locked biometrics for now. */
    data object LockedOut : PromptResult

    /** No screen of the app is in front to show the prompt on. */
    data object NoWindow : PromptResult

    /** The prompt failed for another reason. */
    data object Failed : PromptResult
}
