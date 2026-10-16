package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.MnemonicImport
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The seed-phrase screen's ViewModel: holds the words and the wallet password as they are
 * typed and hands them to [ImportMnemonicAccount]. The one ViewModel the [Mnemonic] and the
 * [WalletPassword] pass through (CLAUDE.md, *Trust*): it never logs, saves or caches them,
 * and drops them from its state as soon as the screen is not shown — hidden ([onHidden]) or
 * gone from the back stack ([onCleared]).
 */
class MnemonicImportViewModel(
    private val importMnemonicAccount: ImportMnemonicAccount,
    private val describeKeyStore: DescribeKeyStore,
) : ViewModel() {

    /** The key store's last answer, kept for the blank state [onHidden] and [onCleared] leave. */
    private var step: PasswordStep = PasswordStep.Checking

    private val state = MutableStateFlow<MnemonicImportUiState>(Entering(Mnemonic.EMPTY))

    val uiState: StateFlow<MnemonicImportUiState> = state.asStateFlow()

    init {
        describe()
    }

    /** The words field now reads [text]. Ignored while an attempt runs and after a success. */
    fun onPhraseChange(text: String) = edit { it.copy(phrase = Mnemonic(text)) }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = edit { it.copy(password = WalletPassword(text)) }

    /** The repeat field (shown while setting the password) now reads [text]. */
    fun onRepeatChange(text: String) = edit { it.copy(repeat = WalletPassword(text)) }

    private fun edit(change: (Entering) -> Entering) {
        state.update { current ->
            if (current is Entering && !current.importing) {
                change(current).copy(error = null, passwordError = null)
            } else {
                current
            }
        }
    }

    /**
     * Add the account the typed words control, its seed stored under the password. Ignored
     * unless [Entering.canImport]. An attempt is not abandoned when the screen is hidden:
     * the use case finishes it as one step, and its outcome lands in the state as it is
     * then, which holds no words once [onHidden] has run.
     */
    fun onImport() {
        val current = state.value as? Entering ?: return
        if (!current.canImport) return
        state.value = current.copy(importing = true, error = null, passwordError = null)
        viewModelScope.launch {
            val result = try {
                importMnemonicAccount(current.phrase, current.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Every port on this path promises not to throw; this is the backstop for a
                // broken promise. The exception's text is not shown: nothing from the key
                // path reaches the screen.
                MnemonicImport.Unavailable
            }
            state.update { now -> if (now is Entering) now.copy(importing = false).after(result) else now }
            // Whatever happened, the key store may have changed: a password set, a key stored.
            if (result !is MnemonicImport.Added) describe()
        }
    }

    /**
     * The screen stopped being shown: the app went to the background or its window was
     * hidden. The seed UiState exists only while its screen is shown (CLAUDE.md, *Trust*), so
     * the words and the password go. An attempt still running goes on without them.
     */
    fun onHidden() {
        state.update { current ->
            if (current is Entering) Entering(Mnemonic.EMPTY, step = step, importing = current.importing) else current
        }
    }

    override fun onCleared() {
        // The screen is gone, and the words and the password go with it.
        state.value = Entering(Mnemonic.EMPTY, step = step)
    }

    private fun describe() {
        viewModelScope.launch {
            val description = try {
                describeKeyStore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyStoreDescription.Unavailable // the port promises not to throw; the backstop
            }
            step = when (description) {
                is KeyStoreDescription.Ready -> description.protection.text.let { protection ->
                    if (description.passwordSet) PasswordStep.Existing(protection) else PasswordStep.New(protection)
                }
                KeyStoreDescription.Unavailable -> PasswordStep.Unavailable
            }
            state.update { current -> if (current is Entering) current.copy(step = step) else current }
        }
    }

    private fun Entering.after(result: MnemonicImport): MnemonicImportUiState = when (result) {
        is MnemonicImport.Added -> Imported(result.account.label)
        is MnemonicImport.AlreadyAdded -> copy(
            error = "“${result.account.label}” is in the wallet already; its key is stored on this device now.",
        )
        is MnemonicImport.Invalid -> copy(error = result.problem.message)
        MnemonicImport.WrongPassword -> copy(passwordError = "That is not the wallet password.")
        MnemonicImport.PasswordTooShort ->
            copy(passwordError = "A wallet password has at least ${WalletPassword.MIN_LENGTH} characters.")
        // After describe() said the key store works, a failure here is most likely passing
        // (memory for Argon2id, a Keystore hiccup): said so, not as a verdict on the device.
        MnemonicImport.Unavailable -> copy(
            error = "The key store failed. Nothing was added. Try again; if it keeps failing, this device " +
                "cannot store the key.",
        )
        MnemonicImport.KeyNotSaved -> copy(error = "The key could not be stored on this device. Nothing was added.")
        MnemonicImport.ListNotSaved -> copy(
            error = "The key is stored, but the account list could not be saved. Add the same words again " +
                "to finish.",
        )
    }

    private val KeyProtection.text: String
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
        }

    private val MnemonicProblem.message: String
        get() = when (this) {
            is MnemonicProblem.WordCount -> "A seed phrase has 12, 15, 18, 21 or 24 words, not $count."
            is MnemonicProblem.UnknownWord -> "Word $position is not in the BIP-39 English word list."
            MnemonicProblem.Checksum ->
                "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."
        }
}
