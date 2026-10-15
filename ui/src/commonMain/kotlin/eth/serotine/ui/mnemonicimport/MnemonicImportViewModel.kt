package eth.serotine.ui.mnemonicimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.MnemonicImport
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Entering
import eth.serotine.ui.mnemonicimport.MnemonicImportUiState.Imported
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The seed-phrase screen's ViewModel: holds the words as they are typed and hands them to
 * [ImportMnemonicAccount]. The one ViewModel the [Mnemonic] passes through (CLAUDE.md,
 * *Trust*): it never logs, saves or caches the words, and drops them when it is cleared,
 * which happens when the screen leaves the back stack.
 */
class MnemonicImportViewModel(private val importMnemonicAccount: ImportMnemonicAccount) : ViewModel() {

    private val state = MutableStateFlow<MnemonicImportUiState>(Entering(Mnemonic.EMPTY))

    val uiState: StateFlow<MnemonicImportUiState> = state.asStateFlow()

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
        viewModelScope.launch {
            state.value = when (val result = importMnemonicAccount(current.phrase)) {
                is MnemonicImport.Added -> Imported(result.account.label)
                is MnemonicImport.AlreadyAdded -> current.copy(
                    importing = false,
                    error = "The first account of these words is already in the wallet, " +
                        "as “${result.account.label}”.",
                )
                is MnemonicImport.Invalid -> current.copy(importing = false, error = result.problem.message)
            }
        }
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
