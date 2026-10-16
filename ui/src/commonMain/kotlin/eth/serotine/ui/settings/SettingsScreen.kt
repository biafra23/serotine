package eth.serotine.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Test tag of the app's version line, for UI tests. */
const val SETTINGS_VERSION_TAG = "settings-version"

/** Binds [SettingsScreen] to its ViewModel. */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, modifier)
}

/** The Settings tab as a pure function of [state]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Network")
            ListItem(
                headlineContent = { Text(state.network) },
                supportingContent = { Text("The only network for now") },
            )
            Section("Light client")
            ListItem(headlineContent = { Text(state.lightClient.text) })
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
