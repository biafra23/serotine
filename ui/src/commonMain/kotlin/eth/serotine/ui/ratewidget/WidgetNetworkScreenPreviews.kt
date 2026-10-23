package eth.serotine.ui.ratewidget

import androidx.compose.runtime.Composable
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The widget's configuration in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, never from the host's ports.
// WidgetNetworkScreenPreviewsTest renders each one in widgetNetworkScreenPreviews, at the end.

private val ethereum = NetworkLabel(1, "Ethereum", testnet = false)
private val gnosis = NetworkLabel(100, "Gnosis Chain", testnet = false)
private val sepolia = NetworkLabel(11_155_111, "Sepolia", testnet = true)

@Composable
private fun WidgetNetworkPreview(state: WidgetNetworkUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        WidgetNetworkScreen(state, onChoose = {}, onCancel = {})
    }
}

@Preview
@Composable
private fun WidgetNetworkChoicesPreview() =
    WidgetNetworkPreview(WidgetNetworkUiState(listOf(ethereum, gnosis, sepolia)))

@Preview
@Composable
private fun WidgetNetworkChoicesDarkPreview() =
    WidgetNetworkPreview(WidgetNetworkUiState(listOf(ethereum, gnosis, sepolia)), darkTheme = true)

@Preview
@Composable
private fun WidgetNetworkNoneOnPreview() = WidgetNetworkPreview(WidgetNetworkUiState(emptyList(), NO_NETWORK_ON))

/** Every preview above, which WidgetNetworkScreenPreviewsTest renders: a new preview goes in here too. */
internal val widgetNetworkScreenPreviews: List<@Composable () -> Unit> = listOf(
    { WidgetNetworkChoicesPreview() },
    { WidgetNetworkChoicesDarkPreview() },
    { WidgetNetworkNoneOnPreview() },
)
