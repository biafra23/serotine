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
        key = "0x" + "6".repeat(64),
        title = "Swapped 100 USDC for at least 4.28845 LINK",
        detail = "On Uniswap · 0x3bFA…e48E",
        about = "0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E",
        time = "Oct 19, 2026, 4:10 PM",
        status = "In block 9,412,190",
        state = ActivityState.DONE,
        opens = "0x" + "6".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "1".repeat(64),
        title = "Sent 0.25 ETH",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "No block seen to include it yet",
        state = ActivityState.WAITING,
        opens = "0x" + "1".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "2".repeat(64),
        title = "Executed a Safe transaction",
        detail = "Safe nonce 4 · Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 18, 2026, 9:15 AM",
        status = "Finalized in block 9,412,118",
        state = ActivityState.DONE,
        opens = "0x" + "2".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "3".repeat(64),
        title = "Sent 40 USDC",
        detail = "To 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Oct 17, 2026, 9:40 PM",
        status = "Reverted in block 9,401,220",
        state = ActivityState.FAILED,
        opens = "0x" + "3".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "4".repeat(64),
        title = "Sent 1 ETH",
        detail = "To Carol · 0xD122…9aDb",
        about = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        time = "Oct 12, 2026, 11:03 AM",
        status = "Mined while the app was not watching, unless another transaction took its nonce",
        state = ActivityState.UNKNOWN,
        opens = "0x" + "4".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "5".repeat(64),
        title = "Deployed a Safe",
        detail = "Safe Vault · 0x5290…9EE7",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "Oct 10, 2026, 8:30 AM",
        status = "No peer confirmed its broadcast",
        state = ActivityState.UNCONFIRMED,
        opens = "0x" + "5".repeat(64),
    ),
)

private const val SAFE_COVERAGE = "Transactions sent from this device. On chain from block 9,400,000 on, " +
    "when it was added here: its events, checked from block 9,410,000 so far; its token transfers."

private const val ACCOUNT_COVERAGE = "Transactions sent from this device. On chain from block 9,400,000 on, " +
    "when it was added here: its token transfers. ETH it received, and transactions sent from it elsewhere, " +
    "are not listed yet."

/** An account's token transfers among a transaction sent from here. */
private val tokenRows = listOf(
    ActivityRow(
        key = "0x" + "b".repeat(64) + ":2",
        title = "Received 250 USDC",
        detail = "From Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Block 9,417,045",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = null,
    ),
    ActivityRow(
        key = "0x" + "c".repeat(64),
        title = "Sent 40.5 USDC",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "Finalized in block 9,412,118",
        state = ActivityState.DONE,
        opens = "0x" + "c".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "d".repeat(64) + ":0",
        title = "Received an unlisted token",
        detail = "From 0xde70…fb77 · token 0x27b1…1c26",
        about = "0xde709f2102306220921060314715629080e2fb77",
        time = "Block 9,408,511",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = null,
    ),
    ActivityRow(
        key = "0x" + "e".repeat(64) + ":5",
        title = "Sent an NFT #7",
        detail = "To Carol · 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Block 9,402,980",
        status = "On chain, from the token's verified logs",
        state = ActivityState.DONE,
        opens = null,
    ),
)

/** A Safe's events among a transaction sent from here, as its Activity merges them by block. */
private val safeRows = listOf(
    ActivityRow(
        key = "0x" + "a".repeat(64) + ":0",
        title = "Module enabled",
        detail = "0x8617…070D",
        about = "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
        time = "Block 9,416,880",
        status = "A module can move this Safe's funds without its owners",
        state = ActivityState.WARNING,
        opens = null,
    ),
    ActivityRow(
        key = "0x" + "6".repeat(64) + ":0",
        title = "Received 0.5 ETH",
        detail = "From Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Block 9,415,302",
        status = "On chain, from the Safe's verified logs",
        state = ActivityState.DONE,
        opens = null,
    ),
    ActivityRow(
        key = "0x" + "7".repeat(64),
        title = "Executed transaction 4",
        detail = "By Alice · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Oct 19, 2026, 2:02 PM",
        status = "In block 9,412,118",
        state = ActivityState.DONE,
        opens = "0x" + "7".repeat(64),
    ),
    ActivityRow(
        key = "0x" + "8".repeat(64) + ":3",
        title = "Owner added",
        detail = "Carol · 0xdbF0…C6FB",
        about = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        time = "Block 9,411,040",
        status = "On chain, from the Safe's verified logs",
        state = ActivityState.DONE,
        opens = null,
    ),
    ActivityRow(
        key = "0x" + "9".repeat(64) + ":1",
        title = "A Safe transaction failed",
        detail = "Safe transaction 0x3f1c…a9e2",
        about = "0x52908400098527886E0F7030069857D2E4169EE7",
        time = "Block 9,410,377",
        status = "Failed on chain: its call reverted",
        state = ActivityState.FAILED,
        opens = null,
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
private fun ActivitySafePreview() = ActivityPreview(ActivityUiState.Shown("Treasury", safeRows, SAFE_COVERAGE))

@Preview
@Composable
private fun ActivitySafeDarkPreview() =
    ActivityPreview(ActivityUiState.Shown("Treasury", safeRows, SAFE_COVERAGE), darkTheme = true)

@Preview
@Composable
private fun ActivityTokensPreview() = ActivityPreview(ActivityUiState.Shown("Alice", tokenRows, ACCOUNT_COVERAGE))

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
    { ActivitySafePreview() },
    { ActivitySafeDarkPreview() },
    { ActivityTokensPreview() },
)
