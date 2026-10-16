package eth.serotine.ui.mnemonicimport

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The seed-phrase screen in Android Studio's preview pane, one preview per state it draws
// differently; MnemonicImportScreenPreviewsTest renders each one. The words and passwords
// are made up: the words are not a seed phrase. A preview has no window to keep out of
// screen capture, so the previews of the words step show the line saying so, which a
// device that blocks capture does not.

private const val WORDS = "one two three four five six seven eight nine ten eleven twelve"

private const val HARDWARE =
    "The seed is stored encrypted under your wallet password and a key in this device's secure hardware: " +
        "both are needed to open it, and a copy of it is useless elsewhere."

private val typed = MnemonicImportUiState.Entering(
    Mnemonic(WORDS),
    password = WalletPassword("correct horse"),
    repeat = WalletPassword("correct horse"),
    step = PasswordStep.New(HARDWARE),
)

@Composable
private fun MnemonicImportPreview(state: MnemonicImportUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        MnemonicImportScreen(
            state = state,
            onPhraseChange = {},
            onPasswordChange = {},
            onRepeatChange = {},
            onImport = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
internal fun MnemonicImportCheckingPreview() = MnemonicImportPreview(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

@Preview
@Composable
internal fun MnemonicImportNewPasswordPreview() = MnemonicImportPreview(typed)

@Preview
@Composable
internal fun MnemonicImportNewPasswordDarkPreview() = MnemonicImportPreview(typed, darkTheme = true)

@Preview
@Composable
internal fun MnemonicImportPasswordHintPreview() = MnemonicImportPreview(
    typed.copy(password = WalletPassword("short"), repeat = WalletPassword.EMPTY),
)

@Preview
@Composable
internal fun MnemonicImportWrongPasswordPreview() = MnemonicImportPreview(
    typed.copy(
        repeat = WalletPassword.EMPTY,
        step = PasswordStep.Existing(HARDWARE),
        passwordError = "That is not the wallet password.",
    ),
)

@Preview
@Composable
internal fun MnemonicImportInvalidWordsPreview() = MnemonicImportPreview(
    typed.copy(error = "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."),
)

@Preview
@Composable
internal fun MnemonicImportImportingPreview() = MnemonicImportPreview(typed.copy(importing = true))

@Preview
@Composable
internal fun MnemonicImportKeyStoreUnavailablePreview() = MnemonicImportPreview(
    MnemonicImportUiState.Entering(Mnemonic(WORDS), step = PasswordStep.Unavailable),
)

@Preview
@Composable
internal fun MnemonicImportImportedPreview() = MnemonicImportPreview(MnemonicImportUiState.Imported("Account 1"))
