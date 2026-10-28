package eth.serotine.ui.addtoken

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Add token in simple mode (docs/simple-ui.md): [AddTokenScreen] for the same [state], with no
 * light-client strip, and what the contract says of itself read on chain with no block. The claim,
 * labelled as the contract's, and Add are as in Detailed view; the block it was read at is behind
 * *Details*.
 */
@Composable
fun SimpleAddTokenScreen(
    state: AddTokenUiState,
    onAddressChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNetwork: (chainId: Long) -> Unit = {},
    onTurnOnNetwork: (chainId: Long) -> Unit = {},
) = AddTokenScreen(
    state.copy(card = (state.card as? TokenCard.Claim)?.let { it.copy(checkedAt = it.simpleCheckedAt) } ?: state.card),
    lightClient = null,
    onAddressChange,
    onAdd,
    onBack,
    modifier,
    onNetwork = onNetwork,
    onTurnOnNetwork = onTurnOnNetwork,
    details = listOfNotNull((state.card as? TokenCard.Claim)?.checkedAt),
)
