package eth.serotine.ui.createaccount

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.components.protectionText
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Create account screen in Android Studio's preview pane, one preview per state it draws
// differently; CreateAccountScreenPreviewsTest renders each one in createAccountScreenPreviews,
// at the end. The words and passwords are made up: the words are not a seed phrase, and the
// passwords say what they are. A preview has no window to keep out of screen capture, so every
// preview shows the line saying so, which a device that blocks capture does not.

private const val WORDS = "one two three four five six seven eight nine ten eleven twelve"

// What the screen says under the password on a device with secure hardware, from the one source.
private val HARDWARE = KeyProtection.HARDWARE.protectionText

private const val PASSWORD = "made-up preview password"

private val phrase = Mnemonic(WORDS)

private val asked = CreateAccountUiState.Checking(phrase, listOf(2, 7, 12), List(3) { Mnemonic.EMPTY })

private val securing = CreateAccountUiState.Securing(
    phrase,
    step = PasswordStep.New(HARDWARE),
    password = WalletPassword(PASSWORD),
    repeat = WalletPassword(PASSWORD),
)

@Composable
private fun CreateAccountPreview(state: CreateAccountUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        CreateAccountScreen(
            state = state,
            onWrittenDown = {},
            onShowAgain = {},
            onCheckedWordChange = { _, _ -> },
            onCheck = {},
            onPasswordChange = {},
            onRepeatChange = {},
            onCreate = {},
            onStartOver = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun CreateAccountPreparingPreview() = CreateAccountPreview(CreateAccountUiState.Preparing)

@Preview
@Composable
private fun CreateAccountShowingPreview() = CreateAccountPreview(CreateAccountUiState.Showing(phrase))

@Preview
@Composable
private fun CreateAccountShowingDarkPreview() =
    CreateAccountPreview(CreateAccountUiState.Showing(phrase), darkTheme = true)

@Preview
@Composable
private fun CreateAccountCheckingPreview() = CreateAccountPreview(asked)

@Preview
@Composable
private fun CreateAccountCheckedWrongPreview() = CreateAccountPreview(
    asked.copy(typed = listOf("two", "eight", "twelve").map(::Mnemonic), wrong = true),
)

@Preview
@Composable
private fun CreateAccountNewPasswordPreview() = CreateAccountPreview(securing)

@Preview
@Composable
private fun CreateAccountPasswordHintPreview() = CreateAccountPreview(
    securing.copy(password = WalletPassword("short"), repeat = WalletPassword.EMPTY),
)

@Preview
@Composable
private fun CreateAccountWrongPasswordPreview() = CreateAccountPreview(
    securing.copy(
        step = PasswordStep.Existing(HARDWARE),
        repeat = WalletPassword.EMPTY,
        passwordError = "That is not the wallet password.",
    ),
)

@Preview
@Composable
private fun CreateAccountCreatingPreview() = CreateAccountPreview(securing.copy(creating = true))

// The password step while the key store has not answered yet (PasswordStep.Checking) draws no
// field, a moment that passes; the one where it stopped answering is a state the user can sit on.
@Preview
@Composable
private fun CreateAccountKeyStoreStoppedPreview() = CreateAccountPreview(
    securing.copy(step = PasswordStep.Unavailable, password = WalletPassword.EMPTY, repeat = WalletPassword.EMPTY),
)

@Preview
@Composable
private fun CreateAccountCreatedPreview() = CreateAccountPreview(CreateAccountUiState.Created("Account 2"))

@Preview
@Composable
private fun CreateAccountRestartPreview() = CreateAccountPreview(
    CreateAccountUiState.Restart(
        "The phrase was cleared when the screen was left, before it was stored, and it will never be used. " +
            "Cross out every word of it you wrote down, then start again with a new phrase.",
    ),
)

@Preview
@Composable
private fun CreateAccountNotListedPreview() = CreateAccountPreview(CreateAccountUiState.NotListed)

@Preview
@Composable
private fun CreateAccountKeyStoreUnavailablePreview() = CreateAccountPreview(CreateAccountUiState.Unavailable)

/** Every preview above, which CreateAccountScreenPreviewsTest renders: a new preview goes in here too. */
internal val createAccountScreenPreviews: List<@Composable () -> Unit> = listOf(
    { CreateAccountPreparingPreview() },
    { CreateAccountShowingPreview() },
    { CreateAccountShowingDarkPreview() },
    { CreateAccountCheckingPreview() },
    { CreateAccountCheckedWrongPreview() },
    { CreateAccountNewPasswordPreview() },
    { CreateAccountPasswordHintPreview() },
    { CreateAccountWrongPasswordPreview() },
    { CreateAccountCreatingPreview() },
    { CreateAccountKeyStoreStoppedPreview() },
    { CreateAccountCreatedPreview() },
    { CreateAccountRestartPreview() },
    { CreateAccountNotListedPreview() },
    { CreateAccountKeyStoreUnavailablePreview() },
)
