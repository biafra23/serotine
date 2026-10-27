package eth.serotine.ui.accountdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SimpleAccountDetailScreen] headless. */
class SimpleAccountDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var refreshes = 0

    private val state = AccountDetailUiState.Ready(
        label = "Main",
        address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        network = NetworkLabel(1, "Ethereum", testnet = false),
        kind = AccountKind.EOA,
        control = Control.FULL_CONTROL,
        total = "€9,612.30",
        totalNote = null,
        assets = listOf(AssetRow("ETH", "Ether", "3.2041 ETH", "eth", true, "block 23,614,077", "€9,612.30")),
        freshness = "Updated 40 s ago · pull down to refresh",
    )

    private fun show(state: AccountDetailUiState) = rule.setContent {
        SerotineTheme {
            SimpleAccountDetailScreen(
                state = state,
                onBack = {},
                onSend = {},
                onSwap = {},
                onReceive = {},
                onCopy = {},
                onRename = {},
                onRemove = {},
                onHideToken = { _, _ -> },
                onMessageShown = {},
                onSort = {},
                onSegment = {},
                onOpenSafeTx = {},
                onImportRequest = {},
                onOpenActivity = {},
                onRefresh = { refreshes++ },
            )
        }
    }

    @Test
    fun noBlockIsNamedAndTheValuesSayHowFreshTheyAre() {
        show(state)

        rule.onNodeWithTag(ACCOUNT_FRESHNESS_TAG).assertTextEquals("Updated 40 s ago · pull down to refresh")
        rule.onNodeWithText("block", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Ethereum").assertIsDisplayed()
    }

    @Test
    fun theRefreshButtonAsksForARefresh() {
        show(state)

        rule.onNodeWithTag(ACCOUNT_REFRESH_TAG).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun anAmountLastVerifiedSaysHowOldItIs() {
        show(
            state.copy(
                assets = listOf(
                    state.assets.single().copy(
                        verified = false,
                        verifiedAt = null,
                        lastKnown = "Last verified Oct 27, 2026, 9:12 AM · may have changed since",
                        ageNote = "3 h ago · may have changed",
                    ),
                ),
            ),
        )

        rule.onNodeWithText("3 h ago · may have changed").assertIsDisplayed()
    }
}
