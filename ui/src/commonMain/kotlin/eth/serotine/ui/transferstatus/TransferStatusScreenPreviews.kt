package eth.serotine.ui.transferstatus

import androidx.compose.runtime.Composable
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The transfer status screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. TransferStatusScreenPreviewsTest renders each one in transferStatusScreenPreviews,
// at the end. The addresses are EIP-55's own examples; the hash, amounts, times and blocks are
// made up.

private const val HASH = "0x5e1f3c0a9b7d2e4f6a8c0b1d3e5f7a9c2b4d6e8f0a1c3e5b7d9f2a4c6e8b0d1f"

private val sent = SentSummary(
    amount = "0.1 ETH",
    symbol = "ETH",
    icon = "eth",
    to = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
    from = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
)

private val signed = StatusStep("Signed", StepState.DONE, "On this device")
private val broadcast = StatusStep("Broadcast", StepState.DONE, "Handed to the network's peers, Oct 17, 2026, 3:04 PM")
private val waitingFinal = StatusStep("Finalized", StepState.WAITING, "Once the beacon chain finalizes its block")

private val pending = TransferStatusUiState.Shown(
    network = "Sepolia",
    hash = HASH,
    sent = sent,
    steps = listOf(
        signed,
        broadcast,
        StatusStep("Included", StepState.WAITING, "Waiting for a block to include it"),
        waitingFinal,
    ),
)

private val included = pending.copy(
    steps = listOf(
        signed,
        broadcast,
        StatusStep("Included", StepState.DONE, "In block 8,412,311, its receipt verified"),
        waitingFinal,
    ),
)

@Composable
private fun TransferStatusPreview(state: TransferStatusUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        TransferStatusScreen(state, onDone = {})
    }
}

@Preview
@Composable
private fun TransferStatusPendingPreview() = TransferStatusPreview(pending)

@Preview
@Composable
private fun TransferStatusPendingDarkPreview() = TransferStatusPreview(pending, darkTheme = true)

@Preview
@Composable
private fun TransferStatusIncludedPreview() = TransferStatusPreview(included)

@Preview
@Composable
private fun TransferStatusFinalizedPreview() = TransferStatusPreview(
    included.copy(
        sent = sent.copy(amount = "40 USDC", symbol = "USDC", icon = "usdc"),
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.DONE, "In block 8,412,311, its receipt verified"),
            StatusStep("Finalized", StepState.DONE, "Block 8,412,311 is finalized"),
        ),
        finished = true,
    ),
)

@Preview
@Composable
private fun TransferStatusFailedPreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.FAILED, "In block 8,412,311, its receipt verified: it reverted"),
            waitingFinal,
        ),
        note = "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same.",
    ),
)

@Preview
@Composable
private fun TransferStatusUnavailablePreview() = TransferStatusPreview(
    pending.copy(
        steps = listOf(
            signed,
            broadcast,
            StatusStep("Included", StepState.UNKNOWN, "Cannot be verified right now"),
            StatusStep("Finalized", StepState.UNKNOWN, "Cannot be verified right now"),
        ),
        note = "Where it stands cannot be verified right now: the light client refuses to sync.",
    ),
)

@Preview
@Composable
private fun TransferStatusNoRecordPreview() = TransferStatusPreview(
    included.copy(sent = null, steps = listOf(signed.copy(detail = null)) + included.steps.drop(1)),
)

@Preview
@Composable
private fun TransferStatusLoadingPreview() = TransferStatusPreview(TransferStatusUiState.Loading("Sepolia"))

/** Every preview above, which TransferStatusScreenPreviewsTest renders: a new preview goes in here too. */
internal val transferStatusScreenPreviews: List<@Composable () -> Unit> = listOf(
    { TransferStatusPendingPreview() },
    { TransferStatusPendingDarkPreview() },
    { TransferStatusIncludedPreview() },
    { TransferStatusFinalizedPreview() },
    { TransferStatusFailedPreview() },
    { TransferStatusUnavailablePreview() },
    { TransferStatusNoRecordPreview() },
    { TransferStatusLoadingPreview() },
)
