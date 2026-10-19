package eth.serotine.ui.activity

import androidx.compose.runtime.Composable
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Activity screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. ActivityScreenPreviewsTest renders each one in activityScreenPreviews, at the end.
// The addresses are EIP-55's own examples, and the hashes made up.

private const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
    "from this account elsewhere, are not listed yet."

private val rows = listOf(
    ActivityRow(
        hash = "0x" + "1".repeat(64),
        title = "Sent 0.25 ETH",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "19 Oct 2026, 14:02",
        status = "No block seen to include it yet",
        state = ActivityState.WAITING,
    ),
    ActivityRow(
        hash = "0x" + "2".repeat(64),
        title = "Executed a Safe transaction",
        detail = "Safe nonce 4 · Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "18 Oct 2026, 09:15",
        status = "Finalized in block 9,412,118",
        state = ActivityState.DONE,
    ),
    ActivityRow(
        hash = "0x" + "3".repeat(64),
        title = "Sent 40 USDC",
        detail = "To 0xdbF0…5bB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "17 Oct 2026, 21:40",
        status = "Reverted in block 9,401,220",
        state = ActivityState.FAILED,
    ),
    ActivityRow(
        hash = "0x" + "4".repeat(64),
        title = "Sent 1 ETH",
        detail = "To Carol · 0xD122…aDb",
        about = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        time = "12 Oct 2026, 11:03",
        status = "Its nonce was used while this device was not watching",
        state = ActivityState.UNKNOWN,
    ),
    ActivityRow(
        hash = "0x" + "5".repeat(64),
        title = "Deployed a Safe",
        detail = "Safe Vault · 0x52908…EE7",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "10 Oct 2026, 08:30",
        status = "No peer confirmed its broadcast",
        state = ActivityState.UNCONFIRMED,
    ),
)

@Composable
private fun ActivityPreview(state: ActivityUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        ActivityScreen(state = state, onBack = {}, onOpen = {})
    }
}

@Preview
@Composable
private fun ActivityListPreview() = ActivityPreview(ActivityUiState.Shown("Alice", rows, COVERAGE))

@Preview
@Composable
private fun ActivityListDarkPreview() =
    ActivityPreview(ActivityUiState.Shown("Alice", rows, COVERAGE), darkTheme = true)

@Preview
@Composable
private fun ActivityEmptyPreview() = ActivityPreview(ActivityUiState.Shown("Alice", emptyList(), COVERAGE))

@Preview
@Composable
private fun ActivityLoadingPreview() = ActivityPreview(ActivityUiState.Loading("Alice"))

@Preview
@Composable
private fun ActivityUnavailablePreview() = ActivityPreview(
    ActivityUiState.Unavailable(
        "Alice",
        "The history of your transactions cannot be read right now. Serotine keeps trying.",
    ),
)

/** Every preview above, which ActivityScreenPreviewsTest renders: a new preview goes in here too. */
internal val activityScreenPreviews: List<@Composable () -> Unit> = listOf(
    { ActivityListPreview() },
    { ActivityListDarkPreview() },
    { ActivityEmptyPreview() },
    { ActivityLoadingPreview() },
    { ActivityUnavailablePreview() },
)
