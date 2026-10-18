package eth.serotine.ui.receive

/** Everything the Receive screen renders (docs/wallet-ux.md, screen 6). */
sealed interface ReceiveUiState {

    /** The account has not been read yet. */
    data object Loading : ReceiveUiState

    /**
     * The account's [label] and its EIP-55 [address], which the QR code carries as it is,
     * and [notice], the warning to send nothing from another network.
     */
    data class Ready(val label: String, val address: String, val notice: String) : ReceiveUiState

    /** The account is not in the wallet (removed): the screen leaves. */
    data object Gone : ReceiveUiState

    /** The stored list cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : ReceiveUiState
}
