package eth.serotine.ui.transferstatus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.shortAddress

/** Test tag of each step, for UI tests. */
const val TRANSFER_STEP_TAG = "transfer-step"

/** Test tag of the transaction hash, for UI tests. */
const val TRANSFER_HASH_TAG = "transfer-hash"

/** Binds [TransferStatusScreen] to its ViewModel; Done and Back end it through [onDone]. */
@Composable
fun TransferStatusRoute(viewModel: TransferStatusViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransferStatusScreen(state, onDone, modifier)
}

/**
 * Send, status (docs/wallet-ux.md, screen 9) as a pure function of [state]: what was sent, and
 * Signed → Broadcast → Included → Finalized, each step with its block once it has one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferStatusScreen(state: TransferStatusUiState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Transfer") },
                actions = {
                    Text(
                        state.network,
                        Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is TransferStatusUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is TransferStatusUiState.Shown -> Shown(state, onDone)
            }
        }
    }
}

@Composable
private fun Shown(state: TransferStatusUiState.Shown, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sent = state.sent
        if (sent != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TokenIcon(sent.icon, sent.symbol, size = 40.dp)
                Text(sent.amount, style = MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Blockie(sent.to, size = 32.dp)
                Column {
                    Text("To", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(sent.to, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Blockie(sent.from, size = 24.dp)
                Text(
                    "From ${shortAddress(sent.from)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
            Text(
                "This device has no record of what was sent: only where the transaction stands is shown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            state.steps.forEach { step -> StepRow(step) }
        }
        state.note?.let { note ->
            Text(
                note,
                color = if (state.steps.any { it.state == StepState.FAILED }) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text("Transaction", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                state.hash,
                Modifier.testTag(TRANSFER_HASH_TAG),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

@Composable
private fun StepRow(step: StatusStep) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.fillMaxWidth().testTag(TRANSFER_STEP_TAG),
        leadingContent = {
            val mark = Modifier.size(24.dp).semantics { contentDescription = step.state.description }
            when (step.state) {
                StepState.DONE -> Icon(Icons.Filled.CheckCircle, null, mark, tint = colors.primary)
                StepState.FAILED -> Icon(Icons.Filled.Warning, null, mark, tint = colors.error)
                StepState.UNKNOWN -> Icon(Icons.Filled.Info, null, mark, tint = colors.onSurfaceVariant)
                StepState.WAITING -> Box(mark, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        },
        headlineContent = { Text(step.label) },
        supportingContent = step.detail?.let { detail -> { Text(detail) } },
    )
}

/** What a step's mark says to accessibility services. */
private val StepState.description: String
    get() = when (this) {
        StepState.DONE -> "Done"
        StepState.WAITING -> "Waiting"
        StepState.FAILED -> "Failed"
        StepState.UNKNOWN -> "Not verified right now"
    }
