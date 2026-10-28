package eth.serotine.ui.safetx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eth.serotine.ui.components.SafeSwapItem

/**
 * A Safe transaction in simple mode (docs/simple-ui.md): [SafeTxScreen] for the same [state], with no
 * light-client strip and no block. The owners' stand, a swap's quote, an approval's or an execution's
 * run and every refusal say they were checked on chain; the blocks, and the review's fee make-up,
 * are behind *Details*. The transaction, decoded here, its hash, what can be done and every check
 * are as in Detailed view.
 */
@Composable
fun SimpleSafeTxScreen(
    state: SafeTxUiState,
    actions: SafeTxActions,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) = SafeTxScreen(
    state = state.simple(),
    lightClient = null,
    actions = actions,
    onBack = onBack,
    onCopy = onCopy,
    onShareText = onShareText,
    modifier = modifier,
    details = state.details(),
)

/** [this] in simple mode's words: each block-naming text in its block-free form. */
private fun SafeTxUiState.simple(): SafeTxUiState = when (this) {
    is SafeTxUiState.Overview -> copy(
        owners = owners.simple(),
        swap = swap?.simple(),
        refusal = simpleRefusal ?: refusal,
        message = simpleMessage ?: message,
    )
    is SafeTxUiState.Signing -> copy(swap = swap?.simple())
    is SafeTxUiState.Review -> copy(
        feeDetail = "",
        balance = balance.copy(verifiedAt = ""),
        simulation = simpleSimulation,
        swap = swap?.simple(),
    )
    else -> this
}

private fun OwnersItem.simple(): OwnersItem = when (this) {
    is OwnersItem.Shown -> copy(
        rows = rows.map { it.copy(detail = it.simpleDetail ?: it.detail) },
        verifiedAt = simpleVerifiedAt,
    )
    is OwnersItem.Unavailable -> copy(message = simpleMessage ?: message)
    OwnersItem.Checking -> this
}

private fun SafeSwapItem.simple() = copy(lines = simpleLines)

/** What each step keeps behind *Details* in simple mode: the blocks Detailed view names. */
private fun SafeTxUiState.details(): List<String> = when (this) {
    is SafeTxUiState.Overview -> buildList {
        (owners as? OwnersItem.Shown)?.let { shown ->
            add(shown.verifiedAt)
            shown.rows.filter { it.simpleDetail != null }.forEach { add("${it.label ?: it.address}: ${it.detail}") }
        }
        (owners as? OwnersItem.Unavailable)?.takeIf { it.simpleMessage != null }?.let { add(it.message) }
        swap?.let { item -> addAll(item.lines.filterNot { it in item.simpleLines }) }
        refusal?.takeIf { simpleRefusal != null }?.let(::add)
        message?.takeIf { simpleMessage != null }?.let(::add)
    }
    is SafeTxUiState.Review -> listOfNotNull(
        feeDetail,
        "${balance.what}: ${balance.before} → ${balance.after}, ${balance.verifiedAt}",
        simulation,
    ) + swap?.let { item -> item.lines.filterNot { it in item.simpleLines } }.orEmpty()
    is SafeTxUiState.Signing -> swap?.let { item -> item.lines.filterNot { it in item.simpleLines } }.orEmpty()
    else -> emptyList()
}
