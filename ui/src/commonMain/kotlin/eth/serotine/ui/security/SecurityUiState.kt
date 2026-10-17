package eth.serotine.ui.security

import eth.serotine.domain.model.WalletPassword

/**
 * Everything Settings → Security renders (docs/wallet-ux.md, screen 21): the wallet password and
 * biometric unlock, display-ready. While the user turns biometric unlock on, [Ready.enabling]
 * holds the password being typed: then the screen takes the password and carries rule 8's
 * protections, and the ViewModel drops it as soon as the screen is not shown.
 */
sealed interface SecurityUiState {

    /** The key store has not answered yet. */
    data object Loading : SecurityUiState

    /**
     * [password] says whether a wallet password is set, and [protection] how this device keeps
     * the keys under it (null before the first key); [biometrics] is the biometric unlock row.
     * [enabling] is the password asked to turn biometric unlock on, [changing] true while it is
     * turned off, and [message] what the last change did.
     */
    data class Ready(
        val password: String,
        val protection: String?,
        val biometrics: BiometricRow,
        val enabling: PasswordPrompt? = null,
        val changing: Boolean = false,
        val message: String? = null,
    ) : SecurityUiState

    /** The key store does not work on this device. */
    data object Unavailable : SecurityUiState
}

/** The biometric unlock row: [status] what it is, [on] the switch, [canChange] whether it may move. */
data class BiometricRow(val status: String, val on: Boolean, val canChange: Boolean)

/**
 * The wallet password asked to turn biometric unlock on; [error] is why the last try was refused,
 * [working] true while it runs.
 */
data class PasswordPrompt(
    val password: WalletPassword = WalletPassword.EMPTY,
    val error: String? = null,
    val working: Boolean = false,
) {
    /** Something is typed and no try is running. */
    val canConfirm: Boolean
        get() = !working && password.text.isNotEmpty()
}
