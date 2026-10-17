package eth.serotine.ui.mnemonicimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.PasswordStep
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the seed-phrase field, for UI tests. */
const val MNEMONIC_FIELD_TAG = "mnemonic-field"

/** Test tags of the password fields, for UI tests. */
const val MNEMONIC_PASSWORD_TAG = "mnemonic-password"
const val MNEMONIC_REPEAT_TAG = "mnemonic-password-repeat"

/** Test tag of the button that adds the account, for UI tests. */
const val MNEMONIC_IMPORT_BUTTON_TAG = "mnemonic-import-button"

/**
 * Binds [MnemonicImportScreen] to its ViewModel, reports a finished import through
 * [onImported] so the caller can close the screen, and tells the ViewModel when the screen
 * stops being shown, so it drops the words.
 */
@Composable
fun MnemonicImportRoute(
    viewModel: MnemonicImportViewModel,
    onBack: () -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: an edit reaches the field in the same frame it was typed. A collector
    // that resumes a frame later hands the field a stale value, which loses keystrokes and
    // moves the cursor while someone types fast.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    MnemonicImportScreen(
        state = state,
        onPhraseChange = viewModel::onPhraseChange,
        onPasswordChange = viewModel::onPasswordChange,
        onRepeatChange = viewModel::onRepeatChange,
        onImport = viewModel::onImport,
        onBack = onBack,
        modifier = modifier,
    )
    if (state is MnemonicImportUiState.Imported) {
        LaunchedEffect(Unit) { onImported() }
    }
}

/**
 * The seed-phrase screen as a function of [state]. While it is shown the window is kept
 * out of screen capture where the platform allows ([protectFromScreenCapture]); where it
 * does not, the screen says so. The field's text is never saved: it lives in the
 * ViewModel's state, not in `rememberSaveable`, so it never reaches a saved-state bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicImportScreen(
    state: MnemonicImportUiState,
    onPhraseChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val captureBlocked = protectFromScreenCapture()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is MnemonicImportUiState.Entering -> EnteringContent(
                state = state,
                captureBlocked = captureBlocked,
                onPhraseChange = onPhraseChange,
                onPasswordChange = onPasswordChange,
                onRepeatChange = onRepeatChange,
                onImport = onImport,
                modifier = Modifier.padding(padding),
            )
            is MnemonicImportUiState.Imported -> Box(Modifier.padding(padding).fillMaxSize()) {
                Text("${state.label} added", Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun EnteringContent(
    state: MnemonicImportUiState.Entering,
    captureBlocked: Boolean,
    onPhraseChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Enter the seed phrase of the account to add: its 12 to 24 words, separated by spaces.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.phrase.text,
            onValueChange = onPhraseChange,
            modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_FIELD_TAG),
            enabled = !state.importing,
            label = { Text("Seed phrase") },
            supportingText = { Text(state.error ?: wordCountLabel(state.wordCount)) },
            isError = state.error != null,
            // A password field to the keyboard: no suggestions, no autocorrect, and nothing
            // learned into the keyboard's dictionary — on the keyboards that honour it.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onImport() }),
            minLines = 3,
        )
        // The password fields appear once the key store has said which one is wanted.
        when (val step = state.step) {
            PasswordStep.Checking -> Unit
            PasswordStep.Unavailable -> Text(
                "The key store does not work on this device, so no account can be added here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is PasswordStep.New, is PasswordStep.Existing -> PasswordFields(
                state = state,
                setting = step is PasswordStep.New,
                onPasswordChange = onPasswordChange,
                onRepeatChange = onRepeatChange,
                onImport = onImport,
            )
        }
        Button(
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_IMPORT_BUTTON_TAG),
        ) {
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Add account")
            }
        }
        Text(
            "Serotine adds the first account of these words (m/44'/60'/0'/0/0). " +
                state.step.protection.orEmpty() + " It cannot send transactions yet.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!captureBlocked) {
            Text(
                "Screenshots and screen recordings of this window cannot be blocked here. Make sure " +
                    "nothing is recording your screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PasswordFields(
    state: MnemonicImportUiState.Entering,
    setting: Boolean,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedTextField(
        value = state.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_PASSWORD_TAG),
        enabled = !state.importing,
        label = { Text(if (setting) "New wallet password" else "Wallet password") },
        supportingText = (state.passwordHint ?: if (setting) "Protects every key this wallet stores" else null)
            ?.let { hint -> { Text(hint) } },
        isError = state.passwordHint != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = if (setting) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onImport() }),
    )
    if (setting) {
        OutlinedTextField(
            value = state.repeat.text,
            onValueChange = onRepeatChange,
            modifier = Modifier.fillMaxWidth().testTag(MNEMONIC_REPEAT_TAG),
            enabled = !state.importing,
            label = { Text("Repeat the password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onImport() }),
        )
    }
}

private val PasswordStep.protection: String?
    get() = when (this) {
        is PasswordStep.New -> protection
        is PasswordStep.Existing -> protection
        PasswordStep.Checking, PasswordStep.Unavailable -> null
    }

private fun wordCountLabel(count: Int): String = when (count) {
    0 -> "No words yet"
    1 -> "1 word"
    else -> "$count words"
}
