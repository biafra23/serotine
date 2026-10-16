package eth.serotine.ui.removeaccount

/** Everything the remove dialog renders. */
sealed interface RemoveAccountUiState {

    /** The account has not been read yet. */
    data object Loading : RemoveAccountUiState

    /**
     * The question, for the account [label]: [keyed] when its key is on this device and goes
     * with it, which the dialog says before anything is removed; [removing] while it is.
     */
    data class Confirm(val label: String, val keyed: Boolean, val removing: Boolean = false) : RemoveAccountUiState

    /** What the user must know about how it went, display-ready, before the dialog closes. */
    data class Notice(val message: String) : RemoveAccountUiState

    /** The account is out of the wallet: the dialog closes. */
    data object Done : RemoveAccountUiState

    /** The account is not in the wallet (any more): the dialog closes. */
    data object Gone : RemoveAccountUiState
}
