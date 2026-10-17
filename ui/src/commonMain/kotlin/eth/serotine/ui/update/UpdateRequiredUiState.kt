package eth.serotine.ui.update

/**
 * Whether the app must show nothing but "update", and what that screen says: display-ready
 * strings only.
 */
sealed interface UpdateRequiredUiState {

    /** This version still follows the network: the app shows its screens. */
    data object NotRequired : UpdateRequiredUiState

    /**
     * This version can no longer follow the network: [text] says why, [keepsData] that
     * updating keeps the wallet, [details] who reports the upgrade, [version] which version
     * this is, and [updateUrl] where the update is.
     */
    data class Required(
        val text: String,
        val details: String,
        val version: String,
        val keepsData: String = KEEPS_DATA,
        val updateUrl: String = RELEASES_URL,
    ) : UpdateRequiredUiState

    private companion object {
        const val KEEPS_DATA = "Your accounts and keys stay on this device: updating the app keeps them."
    }
}
