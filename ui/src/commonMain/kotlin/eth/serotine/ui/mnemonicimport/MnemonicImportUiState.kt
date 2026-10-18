package eth.serotine.ui.mnemonicimport

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.PasswordStep

/**
 * Everything the seed-phrase screen renders: the dedicated seed UiState of the mnemonic
 * exception (CLAUDE.md, *Trust*). It is the only UiState that ever holds a [Mnemonic] or a
 * [WalletPassword], and its ViewModel clears both when the screen is not shown.
 */
sealed interface MnemonicImportUiState {

    /**
     * The words and the password being typed. [step] is which password the key store wants.
     * [error] is why the last attempt was refused, about the words or the import as a whole,
     * and [passwordError] when it was about the password; both stay until something is
     * edited. [importing] is true while an attempt runs.
     */
    data class Entering(
        val phrase: Mnemonic,
        val password: WalletPassword = WalletPassword.EMPTY,
        val repeat: WalletPassword = WalletPassword.EMPTY,
        val step: PasswordStep = PasswordStep.Checking,
        val importing: Boolean = false,
        val error: String? = null,
        val passwordError: String? = null,
    ) : MnemonicImportUiState {

        /** The number of words, counted as the key adapter will; once per state, not per read. */
        val wordCount: Int = phrase.words.size

        /** What is wrong with the password, display-ready, or null. */
        val passwordHint: String?
            get() = passwordError ?: when {
                step !is PasswordStep.New -> null
                password.text.isNotEmpty() && !password.isLongEnough ->
                    "At least ${WalletPassword.MIN_LENGTH} characters"
                repeat.text.isNotEmpty() && repeat.text != password.text -> "The two passwords differ"
                else -> null
            }

        /** True for a BIP-39 word count and a usable password, while no attempt runs. */
        val canImport: Boolean
            get() = !importing && wordCount in Mnemonic.WORD_COUNTS && when (step) {
                PasswordStep.Checking, PasswordStep.Unavailable -> false
                is PasswordStep.New -> password.isLongEnough && repeat.text == password.text
                is PasswordStep.Existing -> password.text.isNotEmpty()
            }
    }

    /**
     * The account named [label] was added. The words and the password are gone; the screen
     * closes, unless there is a [note] to read first (its key protected at a lighter cost than
     * this device's own), which stays until the user is done.
     */
    data class Imported(val label: String, val note: String? = null) : MnemonicImportUiState
}
