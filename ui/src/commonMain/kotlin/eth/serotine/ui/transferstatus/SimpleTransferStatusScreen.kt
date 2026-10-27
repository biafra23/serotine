package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A transfer's status in simple mode (docs/simple-ui.md, S3): [TransferStatusScreen] for the same
 * [state], with no light-client strip and each step in simple mode's words, Sent → Confirmed →
 * Final, without a block. The hash, what was sent and Try again are as in Detailed view.
 */
@Composable
fun SimpleTransferStatusScreen(
    state: TransferStatusUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = TransferStatusScreen(state.simple(), lightClient = null, onDone, onRetry, modifier)

/** [this] with each step's simple label and detail in place of Detailed view's. */
private fun TransferStatusUiState.simple(): TransferStatusUiState = when (this) {
    is TransferStatusUiState.Shown -> copy(
        steps = steps.map {
            it.copy(label = it.simpleLabel ?: it.label, detail = it.simpleDetail ?: it.detail)
        },
    )
    is TransferStatusUiState.Loading -> this
}
