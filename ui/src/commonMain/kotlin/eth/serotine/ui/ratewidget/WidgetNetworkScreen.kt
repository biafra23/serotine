package eth.serotine.ui.ratewidget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.NetworkMark

/** Test tag prefix of a network's row, followed by its chain id, for UI tests. */
const val WIDGET_NETWORK_TAG = "widget-network-"

/**
 * What the widget's configuration shows when a widget is placed (docs/networks.md, *The
 * widget*): the networks it may show the rate of ([choices]: those that are on and have
 * accounts, or, when none is, every one that is on), or why there is none ([message]). A placed widget keeps its network; another one means placing a new widget.
 */
data class WidgetNetworkUiState(
    val choices: List<NetworkLabel>,
    val message: String? = null,
)

/**
 * The widget's configuration as a pure function of [state]: a row per network, [onChoose] with its
 * chain id when tapped; [onCancel] places no widget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetNetworkScreen(
    state: WidgetNetworkUiState,
    onChoose: (chainId: Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                title = { Text("Exchange rate widget") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Which network's rate should this widget show? It keeps the one you choose; for another, " +
                    "place another widget.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            state.choices.forEach { network ->
                NetworkMark(
                    network,
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onChoose(network.chainId) }
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                        .testTag(WIDGET_NETWORK_TAG + network.chainId),
                    style = MaterialTheme.typography.titleMedium,
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * The configuration's state from the networks that are [on] and those [withAccounts] (null: the
 * account list cannot be read), in `Network` order: the networks on with accounts; every one on
 * when none of those is (the wallet has no account, its list cannot be read, or its accounts'
 * networks are all off), since a network that is on can show its rate all the same; with none
 * on, the way to one.
 */
fun widgetNetworkUiState(
    networks: List<NetworkLabel>,
    on: Set<Long>,
    withAccounts: Set<Long>?,
): WidgetNetworkUiState {
    val onNetworks = networks.filter { it.chainId in on }
    val held = withAccounts?.let { accounts -> onNetworks.filter { it.chainId in accounts } }.orEmpty()
    val choices = held.ifEmpty { onNetworks }
    return WidgetNetworkUiState(
        choices,
        message = NO_NETWORK_ON.takeIf { choices.isEmpty() },
    )
}

/** What the configuration says with every network off. */
internal const val NO_NETWORK_ON =
    "No network is on in this wallet, so no rate can be read. Turn one on in Settings → Networks first."
