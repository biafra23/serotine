package eth.serotine.ui.mnemonicimport

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The seed-phrase screen in Android Studio's preview pane, one preview per state it draws
// differently; MnemonicImportScreenPreviewsTest renders each one in
// mnemonicImportScreenPreviews, at the end. The words and passwords are made up: the words
// are not a seed phrase, and the passwords say what they are. A preview has no window to
// keep out of screen capture, so the previews of the words step show the line saying so,
// which a device that blocks capture does not.

private const val WORDS = "one two three four five six seven eight nine ten eleven twelve"

private const val HARDWARE =
    "The seed is stored encrypted under your wallet password and a key in this device's secure hardware: " +
        "both are needed to open it, and a copy of it is useless elsewhere."

private val typed = MnemonicImportUiState.Entering(
    Mnemonic(WORDS),
    password = WalletPassword("made-up preview password"),
    repeat = WalletPassword("made-up preview password"),
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
private fun MnemonicImportCheckingPreview() = MnemonicImportPreview(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

@Preview
@Composable
private fun MnemonicImportNewPasswordPreview() = MnemonicImportPreview(typed)

@Preview
@Composable
private fun MnemonicImportNewPasswordDarkPreview() = MnemonicImportPreview(typed, darkTheme = true)

@Preview
@Composable
private fun MnemonicImportPasswordHintPreview() = MnemonicImportPreview(
    typed.copy(password = WalletPassword("short"), repeat = WalletPassword.EMPTY),
)

@Preview
@Composable
private fun MnemonicImportWrongPasswordPreview() = MnemonicImportPreview(
    typed.copy(
        repeat = WalletPassword.EMPTY,
        step = PasswordStep.Existing(HARDWARE),
        passwordError = "That is not the wallet password.",
    ),
)

@Preview
@Composable
private fun MnemonicImportInvalidWordsPreview() = MnemonicImportPreview(
    typed.copy(error = "These words are not a valid seed phrase. Check for a typo or two words in the wrong order."),
)

@Preview
@Composable
private fun MnemonicImportImportingPreview() = MnemonicImportPreview(typed.copy(importing = true))

@Preview
@Composable
private fun MnemonicImportKeyStoreUnavailablePreview() = MnemonicImportPreview(
    MnemonicImportUiState.Entering(Mnemonic(WORDS), step = PasswordStep.Unavailable),
)

@Preview
@Composable
private fun MnemonicImportImportedPreview() = MnemonicImportPreview(MnemonicImportUiState.Imported("Account 1"))

/** Every preview above, which MnemonicImportScreenPreviewsTest renders: a new preview goes in here too. */
internal val mnemonicImportScreenPreviews: List<@Composable () -> Unit> = listOf(
    { MnemonicImportCheckingPreview() },
    { MnemonicImportNewPasswordPreview() },
    { MnemonicImportNewPasswordDarkPreview() },
    { MnemonicImportPasswordHintPreview() },
    { MnemonicImportWrongPasswordPreview() },
    { MnemonicImportInvalidWordsPreview() },
    { MnemonicImportImportingPreview() },
    { MnemonicImportKeyStoreUnavailablePreview() },
    { MnemonicImportImportedPreview() },
)
