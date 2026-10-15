package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.KeyStoreDescription
import eth.serotine.domain.usecase.MnemonicImport
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The seed-phrase screen's ViewModel: holds the words and the wallet password as they are
 * typed and hands them to [ImportMnemonicAccount]. The one ViewModel the [Mnemonic] and the
 * [WalletPassword] pass through (CLAUDE.md, *Trust*): it never logs, saves or caches them,
 * and drops them as soon as the screen is not shown — hidden ([onHidden]) or gone from the
 * back stack ([onCleared]).
 */
class MnemonicImportViewModel(
    private val importMnemonicAccount: ImportMnemonicAccount,
    describeKeyStore: DescribeKeyStore,
) : ViewModel() {

    private var description: KeyStoreDescription? = null

    private val state = MutableStateFlow<MnemonicImportUiState>(empty())

    val uiState: StateFlow<MnemonicImportUiState> = state.asStateFlow()

    private var attempt: Job? = null

    init {
        viewModelScope.launch {
            val known = describeKeyStore()
            description = known
            state.update { current -> (current as? Entering)?.described(known) ?: current }
        }
    }

    /** The words field now reads [text]. Ignored while an attempt runs and after a success. */
    fun onPhraseChange(text: String) = edit { it.copy(phrase = Mnemonic(text)) }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = edit { it.copy(password = WalletPassword(text)) }

    /** The repeat field (shown while setting the password) now reads [text]. */
    fun onRepeatChange(text: String) = edit { it.copy(repeat = WalletPassword(text)) }

    private fun edit(change: (Entering) -> Entering) {
        state.update { current ->
            if (current is Entering && !current.importing) change(current).copy(error = null) else current
        }
    }

    /**
     * Add the account the typed words control, its seed stored under the password. Ignored
     * unless [Entering.canImport].
     */
    fun onImport() {
        val current = state.value as? Entering ?: return
        if (!current.canImport) return
        state.value = current.copy(importing = true, error = null)
        attempt = viewModelScope.launch {
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
            state.value = when (result) {
                is MnemonicImport.Added -> Imported(result.account.label)
                is MnemonicImport.AlreadyAdded -> current.copy(
                    importing = false,
                    error = "“${result.account.label}” is in the wallet already; its key is stored on this " +
                        "device now.",
                )
                is MnemonicImport.Invalid -> current.copy(importing = false, error = result.problem.message)
                MnemonicImport.WrongPassword -> current.copy(
                    importing = false,
                    error = "That is not the wallet password.",
                )
                MnemonicImport.Unavailable -> current.copy(
                    importing = false,
                    error = "The key store does not work on this device. Nothing was added.",
                )
                MnemonicImport.NotSaved -> current.copy(
                    importing = false,
                    error = "The account could not be saved on this device. Nothing was added.",
                )
            }
        }
    }

    /**
     * The screen stopped being shown: the app went to the background or its window was
     * hidden. The seed UiState exists only while its screen is shown (CLAUDE.md, *Trust*), so
     * the words and the password go, and an attempt still running is abandoned with them.
     */
    fun onHidden() {
        attempt?.cancel()
        attempt = null
        state.update { current -> if (current is Imported) current else empty() }
    }

    override fun onCleared() {
        // The screen is gone, and the words and the password go with it.
        state.value = empty()
    }

    private fun empty(): Entering = Entering(Mnemonic.EMPTY).let { blank ->
        description?.let { known -> blank.described(known) } ?: blank
    }

    private fun Entering.described(known: KeyStoreDescription) =
        copy(settingPassword = !known.passwordSet, protection = known.protection.text)

    private val KeyProtection.text: String
        get() = when (this) {
            KeyProtection.HARDWARE ->
                "The seed is stored encrypted under your wallet password and a key in this device's " +
                    "secure hardware: both are needed to open it, and a copy of it is useless elsewhere."
            KeyProtection.SOFTWARE_KEY_STORE ->
                "The seed is stored encrypted under your wallet password and this device's key store, " +
                    "which is not in secure hardware here (an emulator, or a device without it)."
            KeyProtection.PASSWORD_ONLY ->
                "The seed is stored encrypted under your wallet password alone: this computer has no " +
                    "hardware key store Serotine can use. Choose a strong password."
        }

    private val MnemonicProblem.message: String
        get() = when (this) {
            is MnemonicProblem.WordCount -> "A seed phrase has 12, 15, 18, 21 or 24 words, not $count."
            is MnemonicProblem.UnknownWord -> "Word $position is not in the BIP-39 English word list."
            MnemonicProblem.Checksum ->
                "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."
        }
}
