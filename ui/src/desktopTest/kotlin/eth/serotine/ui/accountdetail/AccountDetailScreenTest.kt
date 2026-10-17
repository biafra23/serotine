package eth.serotine.ui.accountdetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [AccountDetailScreen] headless for its states. */
class AccountDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val ready = AccountDetailUiState.Ready(
        label = "Daily",
        address = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
        kind = AccountKind.EOA,
        control = Control.WATCH_ONLY,
        balance = "1.5 ETH",
        balanceVerified = true,
        balanceNote = "Verified at block 9,412,118",
    )
    private val events = mutableListOf<String>()

    private fun show(state: AccountDetailUiState) = rule.setContent {
        SerotineTheme {
            AccountDetailScreen(
                state,
                onBack = { events += "back" },
                onReceive = { events += "receive" },
                onCopy = { events += "copy $it" },
                onRename = { events += "rename" },
                onRemove = { events += "remove" },
            )
        }
    }

    @Test
    fun showsTheAccountItsBalanceAndWhereItWasVerified() {
        show(ready)

        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNT_BALANCE_TAG).assertTextEquals("1.5 ETH")
        rule.onNodeWithTag(ACCOUNT_ADDRESS_TAG).assertTextEquals(ready.address)
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("Verified at block 9,412,118").assertIsDisplayed()
    }

    @Test
    fun everyActionOfTheLongPressMenuIsAlsoHere() {
        show(ready)

        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithContentDescription("Copy address").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf("receive", "copy ${ready.address}", "rename", "remove"), events)
        rule.onNodeWithText("Address copied").assertIsDisplayed()
    }

    @Test
    fun anUnreadableListIsExplained() {
        show(AccountDetailUiState.Unavailable("Your account list cannot be read right now."))

        rule.onNodeWithText("Your account list cannot be read right now.").assertIsDisplayed()
        rule.onNodeWithContentDescription("More actions").assertDoesNotExist()
    }

    @Test
    fun theAssetsAreListedWithTheirSortAndWhatIsNotKnownYet() {
        var sorted: AssetSort? = null
        rule.setContent {
            SerotineTheme {
                AccountDetailScreen(
                    ready.copy(
                        assets = listOf(
                            AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true),
                            AssetRow("USDC", "USDC", "1250 USDC", "usdc", verified = true),
                            AssetRow("XYZ", "Some token", "3 XYZ", null, verified = true),
                        ),
                        tokensNote = "Checking 1 token",
                    ),
                    {}, {}, {}, {}, {},
                    onSort = { sorted = it },
                )
            }
        }

        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG).assertCountEquals(3)
        rule.onNodeWithText("1250 USDC").assertIsDisplayed()
        rule.onNodeWithText("XY").assertIsDisplayed() // the monogram of a token without an icon
        rule.onNodeWithText("Checking 1 token").assertIsDisplayed()
        rule.onNodeWithText("Amount").performClick()
        assertEquals(AssetSort.AMOUNT, sorted)
    }
}
