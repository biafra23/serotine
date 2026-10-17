package eth.serotine.ui.receive

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Receive screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. ReceiveScreenPreviewsTest renders each one in receiveScreenPreviews, at the end.
// The addresses are EIP-55's own examples. Share shows where the platform has a share sheet,
// which the Safe's preview leaves out, as the desktop does.

private val network = Network.SEPOLIA.displayName

private val daily = ReceiveUiState.Ready(
    "Daily",
    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
    "Only send $network assets to this address",
)

@Composable
private fun ReceivePreview(state: ReceiveUiState, canShare: Boolean = true, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        ReceiveScreen(state = state, onBack = {}, onCopy = {}, onShare = if (canShare) ({}) else null)
    }
}

@Preview
@Composable
private fun ReceiveAccountPreview() = ReceivePreview(daily)

@Preview
@Composable
private fun ReceiveAccountDarkPreview() = ReceivePreview(daily, darkTheme = true)

@Preview
@Composable
private fun ReceiveSafeWithoutSharePreview() = ReceivePreview(
    ReceiveUiState.Ready(
        "Treasury",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        "This Safe exists only on $network",
    ),
    canShare = false,
)

@Preview
@Composable
private fun ReceiveLoadingPreview() = ReceivePreview(ReceiveUiState.Loading)

@Preview
@Composable
private fun ReceiveListUnavailablePreview() = ReceivePreview(
    ReceiveUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying."),
)

/** Every preview above, which ReceiveScreenPreviewsTest renders: a new preview goes in here too. */
internal val receiveScreenPreviews: List<@Composable () -> Unit> = listOf(
    { ReceiveAccountPreview() },
    { ReceiveAccountDarkPreview() },
    { ReceiveSafeWithoutSharePreview() },
    { ReceiveLoadingPreview() },
    { ReceiveListUnavailablePreview() },
)
