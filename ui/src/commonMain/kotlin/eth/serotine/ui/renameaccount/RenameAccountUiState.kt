package eth.serotine.ui.renameaccount

/** Everything the rename dialog renders. */
sealed interface RenameAccountUiState {

    /** The account's current name has not been read yet. */
    data object Loading : RenameAccountUiState

    /** The [name] being typed, the [error] the last save gave, if any, and whether a save is [saving]. */
    data class Editing(val name: String, val error: String? = null, val saving: Boolean = false) : RenameAccountUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : RenameAccountUiState

    /** The new name is saved: the dialog closes. */
    data object Done : RenameAccountUiState

    /** The account is not in the wallet any more: the dialog closes. */
    data object Gone : RenameAccountUiState
}
