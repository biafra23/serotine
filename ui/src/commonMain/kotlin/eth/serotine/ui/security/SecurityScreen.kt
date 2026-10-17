package eth.serotine.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the biometric unlock switch, for UI tests. */
const val SECURITY_BIOMETRIC_SWITCH_TAG = "security-biometric-switch"

/** Test tag of the wallet password field, for UI tests. */
const val SECURITY_PASSWORD_TAG = "security-password"

/**
 * Binds [SecurityScreen] to its ViewModel, and tells it when the screen stops being shown, so a
 * typed password goes.
 */
@Composable
fun SecurityRoute(viewModel: SecurityViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Main.immediate: as on the seed screens, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    SecurityScreen(
        state = state,
        onBiometricsChange = viewModel::onBiometricsChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Settings → Security (docs/wallet-ux.md, screen 21) as a pure function of [state]: whether a
 * wallet password is set and how this device keeps the keys, and the biometric unlock switch.
 * Turning it on asks for the wallet password right here, under rule 8's protections while the
 * field is shown: out of screen capture where the platform allows ([protectFromScreenCapture]),
 * and said so where it does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBiometricsChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Security") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (state) {
                SecurityUiState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(24.dp)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                SecurityUiState.Unavailable -> Text(
                    "The key store does not work on this device, so no key is stored here.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is SecurityUiState.Ready ->
                    ReadyContent(state, onBiometricsChange, onPasswordChange, onConfirm, onCancel)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: SecurityUiState.Ready,
    onBiometricsChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("Wallet password") },
        supportingContent = {
            Column {
                Text(state.password)
                state.protection?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
    ListItem(
        headlineContent = { Text("Biometric unlock") },
        supportingContent = { Text(state.biometrics.status) },
        trailingContent = {
            if (state.changing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = state.biometrics.on || state.enabling != null,
                    onCheckedChange = onBiometricsChange,
                    enabled = state.biometrics.canChange && state.enabling == null,
                    modifier = Modifier.testTag(SECURITY_BIOMETRIC_SWITCH_TAG),
                )
            }
        },
    )
    state.enabling?.let { PasswordPromptContent(it, onPasswordChange, onConfirm, onCancel) }
    state.message?.let { Text(it, Modifier.padding(16.dp)) }
}

@Composable
private fun PasswordPromptContent(
    prompt: PasswordPrompt,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val captureBlocked = protectFromScreenCapture()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Enter the wallet password. It opens each stored key once, so that a key only your fingerprint " +
                "or face unlocks can wrap it. The password keeps working.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = prompt.password.text,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth().testTag(SECURITY_PASSWORD_TAG),
            enabled = !prompt.working,
            label = { Text("Wallet password") },
            supportingText = prompt.error?.let { error -> { Text(error) } },
            isError = prompt.error != null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !prompt.working) { Text("Cancel") }
            Button(onClick = onConfirm, enabled = prompt.canConfirm) {
                if (prompt.working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Turn on")
                }
            }
        }
        if (!captureBlocked) {
            Text(
                "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                    "recording your screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
