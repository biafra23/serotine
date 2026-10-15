package eth.serotine.ui.mnemonicimport

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword

/**
 * Everything the seed-phrase screen renders: the dedicated seed UiState of the mnemonic
 * exception (CLAUDE.md, *Trust*). It is the only UiState that ever holds a [Mnemonic] or a
 * [WalletPassword], and its ViewModel clears both when the screen is not shown.
 */
sealed interface MnemonicImportUiState {

    /**
     * The words and the password being typed. [settingPassword] is null until the key store
     * has said whether a wallet password exists: true when this import sets it (typed twice),
     * false when it must be the existing one. [protection] says, display-ready, how this
     * device will protect the seed. [error] is why the last attempt was refused and stays
     * until something is edited; [importing] is true while an attempt runs.
     */
    data class Entering(
        val phrase: Mnemonic,
        val password: WalletPassword = WalletPassword.EMPTY,
        val repeat: WalletPassword = WalletPassword.EMPTY,
        val settingPassword: Boolean? = null,
        val protection: String? = null,
        val importing: Boolean = false,
        val error: String? = null,
    ) : MnemonicImportUiState {

        /** The number of words, counted as the key adapter will; once per state, not per read. */
        val wordCount: Int = phrase.words.size

        /** Why the password cannot be used yet, display-ready, or null. */
        val passwordHint: String?
            get() = when {
                settingPassword != true -> null
                password.text.isNotEmpty() && !password.isLongEnough ->
                    "At least ${WalletPassword.MIN_LENGTH} characters"
                repeat.text.isNotEmpty() && repeat.text != password.text -> "The two passwords differ"
                else -> null
            }

        /** True for a BIP-39 word count and a usable password, while no attempt runs. */
        val canImport: Boolean
            get() = !importing && wordCount in Mnemonic.WORD_COUNTS && when (settingPassword) {
                null -> false
                true -> password.isLongEnough && repeat.text == password.text
                false -> password.text.isNotEmpty()
            }
    }

    /** The account named [label] was added. The words and the password are gone; the screen closes. */
    data class Imported(val label: String) : MnemonicImportUiState
}
