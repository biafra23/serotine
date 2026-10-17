package eth.serotine.ui.components

import eth.serotine.domain.repository.KeyProtection

/**
 * Which password a seed screen (Import recovery phrase, Create account) asks for, as the key store
 * described itself: the password step both screens draw.
 */
sealed interface PasswordStep {

    /** The key store has not answered yet: no password field, nothing can be added. */
    data object Checking : PasswordStep

    /** This import sets the wallet password, typed twice; [protection] says, display-ready, how the seed is kept. */
    data class New(val protection: String) : PasswordStep

    /** A wallet password is set and must be given; [protection] as for [New]. */
    data class Existing(val protection: String) : PasswordStep

    /** The key store does not work on this device: nothing can be added. */
    data object Unavailable : PasswordStep
}

/** How [this] protects a stored seed, display-ready: what the seed screens say under the password. */
internal val KeyProtection.protectionText: String
    get() = when (this) {
        KeyProtection.HARDWARE ->
            "The seed is stored encrypted under your wallet password and a key in this device's " +
                "secure hardware: both are needed to open it, and a copy of it is useless elsewhere."
        KeyProtection.SOFTWARE_KEY_STORE ->
            "The seed is stored encrypted under your wallet password and this device's key store, " +
                "which is not in secure hardware here (an emulator, or a device without it)."
        KeyProtection.PASSWORD_ONLY ->
            "The seed is stored encrypted under your wallet password alone: Serotine uses no hardware " +
                "key store on this computer. Choose a strong password."
        KeyProtection.PASSWORD_ONLY_TPM_NOT_USED ->
            "The seed is stored encrypted under your wallet password alone: Serotine does not use this " +
                "computer's TPM yet, even if it has one. Choose a strong password."
    }
