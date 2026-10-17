package eth.serotine.ui.currency

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Binds [CurrencyScreen] to its ViewModel; a saved choice ends the screen through [onChosen]. */
@Composable
fun CurrencyRoute(
    viewModel: CurrencyViewModel,
    onBack: () -> Unit,
    onChosen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chosen by rememberUpdatedState(onChosen)
    LaunchedEffect(state.done) {
        if (state.done) chosen()
    }
    CurrencyScreen(state, onChoose = viewModel::choose, onBack = onBack, modifier = modifier)
}

/**
 * The display currency (docs/wallet-ux.md, screen 19) as a pure function of [state]: the
 * currencies the network has a feed for, one chosen. Values only: no amount is ever entered or
 * signed in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyScreen(
    state: CurrencyUiState,
    onChoose: (String) -> Unit,
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
                title = { Text("Display currency") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            state.notice?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            state.options.forEach { option ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = option.chosen,
                        enabled = state.canChoose,
                        role = Role.RadioButton,
                        onClick = { onChoose(option.code) },
                    ),
                    leadingContent = {
                        RadioButton(selected = option.chosen, onClick = null, enabled = state.canChoose)
                    },
                    headlineContent = { Text(option.code) },
                    supportingContent = { Text(option.name) },
                )
            }
            Text(
                "Only currencies with a price feed on this network are offered. Values are shown in it; " +
                    "amounts are always entered and signed in the token's own units.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
