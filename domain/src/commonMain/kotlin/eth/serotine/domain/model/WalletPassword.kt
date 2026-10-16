package eth.serotine.domain.model

/**
 * The wallet password, as the user types it: the factor every stored key is wrapped under
 * (docs/keys-and-signing.md, *The envelope*). It travels like a [Mnemonic] — from the seed
 * screen's UiState through its ViewModel and a use case into the key store, nowhere else —
 * and becomes bytes only inside the key store, which zeroes them after stretching them.
 * The text field's own String cannot be zeroed; that is rule 7's scoped exception.
 *
 * [toString] is redacted and equality is identity, so a log line or a data-class
 * `toString` cannot print it and nothing structurally compares it.
 */
class WalletPassword(val text: String) {

    /** Long enough to be set as a new wallet password. */
    val isLongEnough: Boolean
        get() = text.length >= MIN_LENGTH

    override fun toString(): String = "WalletPassword(redacted)"

    companion object {
        /** The shortest password accepted when one is set. */
        const val MIN_LENGTH = 8

        val EMPTY: WalletPassword = WalletPassword("")
    }
}
