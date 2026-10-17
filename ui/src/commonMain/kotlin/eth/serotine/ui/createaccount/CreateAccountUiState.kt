package eth.serotine.ui.createaccount

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.mnemonicimport.PasswordStep

/**
 * Everything Create account renders (docs/wallet-ux.md, screen 12): a seed UiState of the
 * mnemonic exception (CLAUDE.md, *Trust*), the backup words shown at creation. The states that
 * hold the [Mnemonic] and the [WalletPassword] exist only while the screen is shown: its
 * ViewModel replaces them with [Restart] as soon as it is not.
 */
sealed interface CreateAccountUiState {

    /** The key adapter is making the phrase. */
    data object Preparing : CreateAccountUiState

    /** The new [phrase], shown once, to be written down. */
    data class Showing(val phrase: Mnemonic) : CreateAccountUiState {
        /** The words, numbered on screen in this order. */
        val words: List<String> = phrase.words
    }

    /**
     * Three of the words asked back: the 1-based [positions] and what is [typed] for each;
     * [wrong] once a check did not match what was written down.
     */
    data class Checking(
        val phrase: Mnemonic,
        val positions: List<Int>,
        val typed: List<String>,
        val wrong: Boolean = false,
    ) : CreateAccountUiState {
        /** Every word is typed. */
        val canCheck: Boolean
            get() = typed.all { it.isNotBlank() }
    }

    /**
     * The wallet password for the account's key: set and repeated on the first key ([step]
     * [PasswordStep.New]), the wallet's own on a later one. [creating] while the key is
     * stored; [error] and [passwordError] why the last attempt was refused.
     */
    data class Securing(
        val phrase: Mnemonic,
        val step: PasswordStep,
        val password: WalletPassword = WalletPassword.EMPTY,
        val repeat: WalletPassword = WalletPassword.EMPTY,
        val creating: Boolean = false,
        val error: String? = null,
        val passwordError: String? = null,
    ) : CreateAccountUiState {

        /** What is wrong with the password, display-ready, or null. */
        val passwordHint: String?
            get() = passwordError ?: when {
                step !is PasswordStep.New -> null
                password.text.isNotEmpty() && !password.isLongEnough ->
                    "At least ${WalletPassword.MIN_LENGTH} characters"
                repeat.text.isNotEmpty() && repeat.text != password.text -> "The two passwords differ"
                else -> null
            }

        /** A usable password, while no attempt runs. */
        val canCreate: Boolean
            get() = !creating && when (step) {
                PasswordStep.Checking, PasswordStep.Unavailable -> false
                is PasswordStep.New -> password.isLongEnough && repeat.text == password.text
                is PasswordStep.Existing -> password.text.isNotEmpty()
            }
    }

    /** The screen was left while the key was being stored: that finishes, without the words or the password. */
    data object Finishing : CreateAccountUiState

    /** The account named [label] was created; the words and the password are gone. */
    data class Created(val label: String) : CreateAccountUiState

    /**
     * The phrase is gone, for the reason [message] gives (the screen was left; an attempt
     * failed): nothing of it is kept. Starting again makes a new one.
     */
    data class Restart(val message: String) : CreateAccountUiState

    /** The key store does not work on this device: no account can be created. */
    data object Unavailable : CreateAccountUiState
}
