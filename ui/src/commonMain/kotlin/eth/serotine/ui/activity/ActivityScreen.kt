package eth.serotine.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.lightclient.LightClientViewModel

/** Test tag of each transaction row, for UI tests. */
const val ACTIVITY_ROW_TAG = "activity-row"

/** Binds [ActivityScreen] to its ViewModel; a row opens its detail ([onOpen], by its key). */
@Composable
fun ActivityRoute(
    viewModel: ActivityViewModel,
    lightClient: LightClientViewModel,
    onBack: () -> Unit,
    onOpen: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strip by lightClient.item.collectAsStateWithLifecycle()
    ActivityScreen(state, strip, onBack = onBack, onOpen = onOpen, modifier = modifier)
}

/**
 * An account's Activity (docs/wallet-ux.md, screen 25) as a pure function of [state]: what the
 * list covers, then each transaction this device sent for it and each event of a Safe on chain,
 * newest first, with how it ended as this device saw it; a tap on a row opens its detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    lightClient: LightClientItem,
    onBack: () -> Unit,
    onOpen: (ActivityKey) -> Unit,
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
                title = {
                    Column {
                        Text("Activity")
                        Text(state.title, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        UnderLightClientStrip(lightClient, Modifier.padding(padding)) {
            ActivityContent(state, onOpen)
        }
    }
}

/** What the history says, under the strip. */
@Composable
private fun BoxScope.ActivityContent(state: ActivityUiState, onOpen: (ActivityKey) -> Unit) {
    when (state) {
        is ActivityUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is ActivityUiState.Unavailable -> Text(
            state.message,
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
        is ActivityUiState.Shown -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    state.coverage,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.rows.isEmpty()) {
                item {
                    Text(
                        state.empty,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.rows, key = { it.key }) { row ->
                ActivityItem(row, onClick = { onOpen(row.opens) })
            }
        }
    }
}

/** One row; a tap opens its detail ([onClick]). */
@Composable
private fun ActivityItem(row: ActivityRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(ACTIVITY_ROW_TAG).clickable(onClick = onClick),
        leadingContent = { AccountAvatar(row.about) },
        // When, above what it did: beside it, the date would squeeze each row into narrow lines.
        overlineContent = { Text(row.time) },
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text(row.detail)
                Text(row.status, style = MaterialTheme.typography.labelSmall, color = statusColor(row.state))
            }
        },
    )
}

/**
 * The colour a row's status is said in, here and on its detail: an error's for a revert or a
 * warning, muted for what is not known yet or calls for care.
 */
@Composable
internal fun statusColor(state: ActivityState): Color = when (state) {
    ActivityState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    ActivityState.FAILED, ActivityState.WARNING -> MaterialTheme.colorScheme.error
    ActivityState.WAITING, ActivityState.UNCONFIRMED, ActivityState.UNKNOWN, ActivityState.CAUTION ->
        MaterialTheme.colorScheme.tertiary
}
