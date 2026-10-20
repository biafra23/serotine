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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.LightClientHealthDot
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.unavailable

/** Test tag of the app's version line, for UI tests. */
const val SETTINGS_VERSION_TAG = "settings-version"

/** Test tag of the row that switches the heavy downloads on a metered connection, for UI tests. */
const val SETTINGS_METERED_TAG = "settings-metered"

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
    SettingsScreen(state, onCurrency, onTokens, onSecurity, viewModel::allowHeavyDownloadsOnMetered, modifier)
}

/**
 * The Settings tab as a pure function of [state]; the display currency opens through
 * [onCurrency], Tokens through [onTokens], and Security (the wallet password, biometric unlock)
 * through [onSecurity]. The switch for the heavy downloads on a metered connection calls
 * [onMeteredDownloads] with the new choice; greyed out, a tap says why it cannot be changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onCurrency: () -> Unit,
    onTokens: () -> Unit,
    onSecurity: () -> Unit,
    onMeteredDownloads: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var unavailable by remember { mutableStateOf<String?>(null) }
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
            MeteredDownloads(state.meteredDownloads, onMeteredDownloads, onUnavailable = { unavailable = it })
            Section("Light client")
            ListItem(
                leadingContent = { LightClientHealthDot(state.lightClient.health) },
                headlineContent = { Text(state.lightClient.text) },
            )
            Section("About")
            ListItem(
                // One merged node: a screen reader announces "Serotine, version 0.1.0".
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(SETTINGS_VERSION_TAG),
                headlineContent = { Text("Serotine") },
                supportingContent = { Text("Version ${state.version}") },
            )
        }
    }
    unavailable?.let { reason ->
        UnavailableDialog(UnavailableNotice(METERED_DOWNLOADS_TITLE, reason), onDismiss = { unavailable = null })
    }
}

private const val METERED_DOWNLOADS_TITLE = "Heavy downloads on metered connections"

/**
 * The switch for the heavy downloads on a metered connection: the whole row toggles it, or, greyed
 * out, says why it cannot; while [item] is not read yet ([SettingsUiState.meteredDownloads] null)
 * it is greyed out and takes no tap.
 */
@Composable
private fun MeteredDownloads(
    item: MeteredDownloadsItem?,
    onChange: (Boolean) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val reason = item?.unavailableReason
    val enabled = item != null && reason == null
    val row = when {
        item == null -> Modifier
        reason == null -> Modifier.toggleable(value = item.on, role = Role.Switch, onValueChange = onChange)
        else -> Modifier.clickable { onUnavailable(reason) }.unavailable(true)
    }
    val colors = if (enabled) {
        ListItemDefaults.colors()
    } else {
        ListItemDefaults.colors(
            headlineColor = Unavailable.contentColor(),
            supportingColor = Unavailable.contentColor(),
        )
    }
    ListItem(
        modifier = row.testTag(SETTINGS_METERED_TAG),
        colors = colors,
        headlineContent = { Text(METERED_DOWNLOADS_TITLE) },
        supportingContent = item?.let { { Text(it.text) } },
        trailingContent = {
            // The row takes the tap, so a greyed switch still says why.
            Switch(
                checked = item?.on == true,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
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
