package eth.serotine.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Test tag of the Debug tab's row, for UI tests. */
const val SETTINGS_DEBUG_SCREEN_TAG = "settings-debug-screen"

/** Test tag of a network's row, followed by its chain id, for UI tests. */
const val SETTINGS_NETWORK_TAG = "settings-network-"

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
    SettingsScreen(
        state,
        onCurrency,
        onTokens,
        onSecurity,
        viewModel::allowScreenCapture,
        viewModel::showDebugScreen,
        modifier,
        onNetworkChange = viewModel::switchNetwork,
        onConfirmTurnOff = viewModel::confirmTurnOff,
        onCancelTurnOff = viewModel::cancelTurnOff,
    )
}

/**
 * The Settings tab as a pure function of [state]; the display currency opens through
 * [onCurrency], Tokens through [onTokens], and Security (the wallet password, biometric unlock)
 * through [onSecurity]. A switch shows the Debug tab through [onDebugScreenChange]. In a debug
 * build, a switch allows screen recordings of the protected screens through [onScreenCaptureChange].
 * Each network's switch turns it on or off through [onNetworkChange] (its chain id, and whether
 * on); the confirmation before a network with accounts is turned off answers through
 * [onConfirmTurnOff] and [onCancelTurnOff] (docs/networks.md, N16 and N20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    onScreenCaptureChange: (Boolean) -> Unit,
    onDebugScreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onNetworkChange: (chainId: Long, on: Boolean) -> Unit = { _, _ -> },
    onConfirmTurnOff: () -> Unit = {},
    onCancelTurnOff: () -> Unit = {},
) {
    state.turningOff?.let { asked ->
        AlertDialog(
            onDismissRequest = onCancelTurnOff,
            title = { Text(asked.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(asked.text)
                    asked.waiting?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmTurnOff) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = onCancelTurnOff) { Text("Cancel") } },
        )
    }
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
            Section("Networks")
            state.networks.forEach { network ->
                ListItem(
                    modifier = Modifier
                        .toggleable(
                            value = network.on,
                            role = Role.Switch,
                            onValueChange = { onNetworkChange(network.chainId, it) },
                        )
                        .testTag(SETTINGS_NETWORK_TAG + network.chainId),
                    leadingContent = { LightClientHealthDot(network.health) },
                    headlineContent = { Text(if (network.testnet) "${network.name} · Testnet" else network.name) },
                    supportingContent = {
                        Column {
                            Text(network.line)
                            if (network.notSaved) {
                                Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    // The row is the toggle: the switch only shows its state.
                    trailingContent = { Switch(checked = network.on, onCheckedChange = null) },
                )
            }
            Text(
                "A network that is off checks nothing; its accounts stay in your list.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section("Developer")
            ListItem(
                modifier = Modifier
                    .toggleable(value = state.debugScreen, role = Role.Switch, onValueChange = onDebugScreenChange)
                    .testTag(SETTINGS_DEBUG_SCREEN_TAG),
                headlineContent = { Text("Debug tab") },
                supportingContent = {
                    Column {
                        Text(
                            "Adds a tab with a log of the light client's requests, kept in memory on this device " +
                                "only. Turning it off empties the log.",
                        )
                        if (state.debugScreenNotSaved) {
                            Text("The change could not be saved.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                // The row is the toggle: the switch only shows its state.
                trailingContent = { Switch(checked = state.debugScreen, onCheckedChange = null) },
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
