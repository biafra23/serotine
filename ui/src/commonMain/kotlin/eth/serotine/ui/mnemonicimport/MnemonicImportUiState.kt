package eth.serotine.ui.mnemonicimport

import eth.serotine.domain.model.Mnemonic

/**
 * Everything the seed-phrase screen renders: the dedicated seed UiState of the mnemonic
 * exception (CLAUDE.md, *Trust*). It is the only UiState that ever holds a [Mnemonic], and
 * its ViewModel clears it when the screen is left.
 */
sealed interface MnemonicImportUiState {

    /**
     * The words being typed. [error] is why the last attempt was refused, display-ready,
     * and stays until the words change; [importing] is true while an attempt runs.
     */
    data class Entering(
        val phrase: Mnemonic,
        val importing: Boolean = false,
        val error: String? = null,
    ) : MnemonicImportUiState {

        /** The number of words, counted as the key adapter will; once per state, not per read. */
        val wordCount: Int = phrase.words.size

        /** True for a word count BIP-39 defines, while no attempt runs. */
        val canImport: Boolean
            get() = !importing && wordCount in Mnemonic.WORD_COUNTS
    }

    /** The account named [label] was added. The words are gone; the screen closes. */
    data class Imported(val label: String) : MnemonicImportUiState
}
