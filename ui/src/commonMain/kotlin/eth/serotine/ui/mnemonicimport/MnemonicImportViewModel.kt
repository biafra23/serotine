package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.usecase.ImportMnemonicAccount
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
 * The seed-phrase screen's ViewModel: holds the words as they are typed and hands them to
 * [ImportMnemonicAccount]. The one ViewModel the [Mnemonic] passes through (CLAUDE.md,
 * *Trust*): it never logs, saves or caches the words, and drops them as soon as the screen
 * is not shown — hidden ([onHidden]) or gone from the back stack ([onCleared]).
 */
class MnemonicImportViewModel(private val importMnemonicAccount: ImportMnemonicAccount) : ViewModel() {

    private val state = MutableStateFlow<MnemonicImportUiState>(Entering(Mnemonic.EMPTY))

    val uiState: StateFlow<MnemonicImportUiState> = state.asStateFlow()

    private var attempt: Job? = null

    /** The field now reads [text]. Ignored while an attempt runs and after a success. */
    fun onPhraseChange(text: String) {
        state.update { current ->
            if (current is Entering && !current.importing) Entering(Mnemonic(text)) else current
        }
    }

    /** Add the account the typed words control. Ignored unless [Entering.canImport]. */
    fun onImport() {
        val current = state.value as? Entering ?: return
        if (!current.canImport) return
        state.value = current.copy(importing = true, error = null)
        attempt = viewModelScope.launch {
            val result = try {
                importMnemonicAccount(current.phrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Every port on this path promises not to throw; this is the backstop for a
                // broken promise, so the screen shows an error instead of spinning forever. The
                // exception's text is not shown: nothing from the key path reaches the screen.
                MnemonicImport.Unavailable
            }
            state.value = when (result) {
                is MnemonicImport.Added -> Imported(result.account.label)
                is MnemonicImport.AlreadyAdded -> current.copy(
                    importing = false,
                    error = "The first account of these words is already in the wallet, " +
                        "as “${result.account.label}”.",
                )
                is MnemonicImport.Invalid -> current.copy(importing = false, error = result.problem.message)
                MnemonicImport.Unavailable -> current.copy(
                    importing = false,
                    error = "The account could not be derived on this device.",
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
     * the words go, and an attempt still running is abandoned with them.
     */
    fun onHidden() {
        attempt?.cancel()
        attempt = null
        state.update { current -> if (current is Imported) current else Entering(Mnemonic.EMPTY) }
    }

    override fun onCleared() {
        // The screen is gone, and the words go with it.
        state.value = Entering(Mnemonic.EMPTY)
    }

    private val MnemonicProblem.message: String
        get() = when (this) {
            is MnemonicProblem.WordCount -> "A seed phrase has 12, 15, 18, 21 or 24 words, not $count."
            is MnemonicProblem.UnknownWord -> "Word $position is not in the BIP-39 English word list."
            MnemonicProblem.Checksum ->
                "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."
        }
}
