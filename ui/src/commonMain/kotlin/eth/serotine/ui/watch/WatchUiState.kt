package eth.serotine.ui.watch

import eth.serotine.ui.components.Control

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
    /** Only a verified account or Safe can be added, and one add at a time. */
    val canAdd: Boolean
        get() = (detection is Detection.Account || detection is Detection.Safe) && !adding
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

    /**
     * A Safe at the EIP-55 [address], verified at the block [checkedAt] names: its [title]
     * ("Safe 1.4.1"), who must sign ([signers]: "2 of 3 owners"), its EIP-55 [owners], its
     * [modules] — each can move its funds without the owners — its [guard] and [moduleGuard]
     * (null: none), the [control] the account will get, by the owners' keys held here, and the
     * [threshold] badge its blockie carries ("2/3").
     */
    data class Safe(
        val address: String,
        val title: String,
        val signers: String,
        val owners: List<String>,
        val modules: List<String>,
        val guard: String?,
        val checkedAt: String,
        val control: Control,
        val moduleGuard: String? = null,
        val threshold: String = "",
    ) : Detection

    /** A contract that is not a recognised Safe, which cannot be watched. */
    data class Contract(val message: String) : Detection

    /** The address is in the wallet already, as [label]. */
    data class InWallet(val label: String) : Detection

    /** No verified answer right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : Detection
}
