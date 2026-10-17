package eth.serotine.domain.model

/**
 * BIP-39 backup words, as the user types them to restore an account, or as the key adapter
 * made them for Create account. The ONE type the words travel as, which is the condition of
 * the mnemonic-screen exception to the key-material rule (CLAUDE.md, *Trust*;
 * docs/keys-and-signing.md, *Backup and recovery*): between the key adapter and the seed
 * screens' UiStates, through their ViewModels and a use case, and nowhere else, so a search
 * for this type finds the whole path.
 *
 * It holds [text] exactly as typed, because the input field shows it back, and reads
 * [words] from it. Whether the words are a valid mnemonic (the wordlist, the checksum) is
 * the key adapter's decision: a [Mnemonic] is whatever was typed.
 *
 * [toString] is redacted and equality is identity, so neither a log line nor the
 * generated `toString` of a state that holds one can print the words.
 */
class Mnemonic(val text: String) {

    /**
     * The words of [text]: lower-cased and split on any run of whitespace or Unicode space
     * separator (the no-break spaces that text pasted from a web page or a PDF carries).
     */
    val words: List<String>
        get() = text.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    override fun toString(): String = "Mnemonic(redacted)"

    companion object {
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")

        /** Nothing typed yet. */
        val EMPTY: Mnemonic = Mnemonic("")

        /** The lengths BIP-39 defines: 128 to 256 bits of entropy in steps of 32. */
        val WORD_COUNTS: Set<Int> = setOf(12, 15, 18, 21, 24)
    }
}

/** Why a [Mnemonic] is not a BIP-39 mnemonic, as the key adapter reports it. */
sealed interface MnemonicProblem {

    /** [count] words, which is none of [Mnemonic.WORD_COUNTS]. */
    data class WordCount(val count: Int) : MnemonicProblem

    /** The word at [position] (1-based) is not in the BIP-39 English wordlist. */
    data class UnknownWord(val position: Int) : MnemonicProblem

    /**
     * Every word is in the list, but the checksum the last word carries does not match:
     * a typo that happens to be another list word, or words in the wrong order.
     */
    data object Checksum : MnemonicProblem
}
