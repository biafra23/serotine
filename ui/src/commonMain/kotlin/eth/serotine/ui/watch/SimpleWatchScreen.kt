package eth.serotine.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Watch address in simple mode (docs/simple-ui.md): [WatchScreen] for the same [state], with no
 * light-client strip and no block: each card says it was checked on chain, and a name says what it
 * resolves to the same way. What is at the address, and whether it can be added, are the same as in
 * Detailed view.
 */
@Composable
fun SimpleWatchScreen(
    state: WatchUiState,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLookup: () -> Unit = {},
    onChooseNetworks: () -> Unit = {},
    onNetworkToggle: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
) = WatchScreen(
    state.simple(),
    lightClient = null,
    onAddressChange,
    onNameChange,
    onAdd,
    onBack,
    modifier,
    onRetryLookup = onRetryLookup,
    onChooseNetworks = onChooseNetworks,
    onNetworkToggle = onNetworkToggle,
    onTurnOnNetwork = onTurnOnNetwork,
)

/** [this] in simple mode's words: the cards checked on chain, and the field's note, with no block. */
private fun WatchUiState.simple(): WatchUiState = copy(
    addressNote = simpleAddressNote ?: addressNote,
    detection = detection?.simple(),
    networks = networks.map { it.copy(detection = it.detection.simple()) },
)

private fun Detection.simple(): Detection = when (this) {
    is Detection.Account -> copy(checkedAt = CHECKED_ON_CHAIN)
    is Detection.Safe -> copy(checkedAt = CHECKED_ON_CHAIN)
    else -> this
}

/** What a card says in place of its block: what it shows was read from the chain and proven. */
private const val CHECKED_ON_CHAIN = "Checked on chain"
