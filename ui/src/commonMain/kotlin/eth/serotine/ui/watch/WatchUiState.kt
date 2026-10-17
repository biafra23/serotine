package eth.serotine.ui.watch

/**
 * Everything the Watch address screen renders (docs/wallet-ux.md, screen 10): what is typed
 * ([address], [name]), why the address cannot be read ([addressError]), what the light client
 * says is at it ([detection], null until the text is an address), and how adding went.
 */
data class WatchUiState(
    val address: String = "",
    val name: String = "",
    val addressError: String? = null,
    val detection: Detection? = null,
    val adding: Boolean = false,
    val nameError: String? = null,
    val error: String? = null,
    /** Added: the screen leaves. */
    val done: Boolean = false,
) {
    /** Only a verified account can be added, and one add at a time. */
    val canAdd: Boolean
        get() = detection is Detection.Account && !adding
}

/** The detection card: what is at the typed address, display-ready. */
sealed interface Detection {

    /** The light client is being asked. */
    data object Checking : Detection

    /**
     * No contract code at the EIP-55 [address] — an account a key controls, or a contract not
     * deployed yet — verified at the block [checkedAt] names; [delegate] is the EIP-55
     * address its EIP-7702 delegation points to, if it has one.
     */
    data class Account(val address: String, val checkedAt: String, val delegate: String?) : Detection

    /** A contract, which cannot be watched (yet). */
    data class Contract(val message: String) : Detection

    /** The address is in the wallet already, as [label]. */
    data class InWallet(val label: String) : Detection

    /** No verified answer right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : Detection
}
