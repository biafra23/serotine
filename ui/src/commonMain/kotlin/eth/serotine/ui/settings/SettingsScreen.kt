package eth.serotine.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.LightClientHealthDot

/** Test tag of the app's version line, for UI tests. */
const val SETTINGS_VERSION_TAG = "settings-version"

/** Test tag of the debug build's screen-recording row, for UI tests. */
const val SETTINGS_SCREEN_CAPTURE_TAG = "settings-screen-capture"

/** Binds [SettingsScreen] to its ViewModel. */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, onCurrency, onTokens, onSecurity, viewModel::allowScreenCapture, modifier)
}

/**
 * The Settings tab as a pure function of [state]; the display currency opens through
 * [onCurrency], Tokens through [onTokens], and Security (the wallet password, biometric unlock)
 * through [onSecurity]. In a debug build, a switch allows screen recordings of the protected
 * screens through [onScreenCaptureChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    onScreenCaptureChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Display")
            ListItem(
                modifier = Modifier.clickable(onClick = onCurrency),
                headlineContent = { Text("Display currency") },
                supportingContent = { Text(state.currency) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onTokens),
                headlineContent = { Text("Tokens") },
                supportingContent = { Text(state.tokens) },
            )
            Section("Keys")
            ListItem(
                modifier = Modifier.clickable(onClick = onSecurity),
                headlineContent = { Text("Security") },
                supportingContent = { Text("Wallet password · Biometric unlock") },
            )
            Section("Network")
            ListItem(
                headlineContent = { Text(state.network) },
                supportingContent = { Text("The only network for now") },
            )
            Section("Light client")
            ListItem(
                leadingContent = { LightClientHealthDot(state.lightClient.health) },
                headlineContent = { Text(state.lightClient.text) },
            )
            state.screenCaptureAllowed?.let { allowed ->
                Section("Debug build")
                ListItem(
                    modifier = Modifier
                        .toggleable(value = allowed, role = Role.Switch, onValueChange = onScreenCaptureChange)
                        .testTag(SETTINGS_SCREEN_CAPTURE_TAG),
                    headlineContent = { Text("Allow screen recording") },
                    supportingContent = {
                        Text(
                            "Lets screenshots and screen recordings capture the screens that show a recovery " +
                                "phrase or take the wallet password. Off again when the app restarts.",
                        )
                    },
                    // The row is the toggle: the switch only shows its state.
                    trailingContent = { Switch(checked = allowed, onCheckedChange = null) },
                )
            }
            Section("About")
            ListItem(
                // One merged node: a screen reader announces "Serotine, version 0.1.0".
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(SETTINGS_VERSION_TAG),
                headlineContent = { Text("Serotine") },
                supportingContent = { Text("Version ${state.version}") },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
