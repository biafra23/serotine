package eth.serotine.ui.accountdetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
        total = "$3,000.00",
        totalNote = "Testnet prices · 1 asset without a price",
        pricesNote = "Prices: Chainlink feeds, read through the light client, testnet prices",
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
                onHideToken = { contract, symbol -> events += "hide $symbol $contract" },
                onMessageShown = { events += "message shown" },
                onSort = { events += "sort $it" },
                onSegment = { events += "segment $it" },
            )
        }
    }

    @Test
    fun showsTheAccountItsTotalAndWhatTheTotalLeavesOut() {
        show(ready)

        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertTextEquals("$3,000.00")
        rule.onNodeWithTag(ACCOUNT_ADDRESS_TAG).assertTextEquals(ready.address)
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("Testnet prices · 1 asset without a price").assertIsDisplayed()
        rule.onNodeWithText("Prices: Chainlink feeds, read through the light client, testnet prices").assertExists()
    }

    @Test
    fun eachRowHasItsValueAndTheRowsAreSortedByValueOrName() {
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, "block 9", value = "$3,000.00"),
                    AssetRow("UNI", "Uniswap", "2 UNI", "uni", verified = true, "block 9", value = "No price"),
                ),
            ),
        )

        // The header's total and the ETH row's value.
        rule.onAllNodesWithText("$3,000.00", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("No price", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Name").performClick()
        assertEquals(listOf("sort NAME"), events)
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
    fun theAssetsAreListedEachWithItsBlockAndWhatIsNotKnownYet() {
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, verifiedAt = "block 9"),
                    AssetRow("USDC", "USDC", "1250 USDC", "usdc", verified = true, verifiedAt = "block 12"),
                    AssetRow("XYZ", "Some token", "3 XYZ", null, verified = true),
                ),
                tokensNote = "Checking 1 token",
            ),
        )

        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG).assertCountEquals(3)
        rule.onNodeWithText("1250 USDC").assertIsDisplayed()
        rule.onNodeWithText("USDC · verified at block 12").assertIsDisplayed()
        rule.onNodeWithText("XY").assertIsDisplayed() // the monogram of a token without an icon
        rule.onNodeWithText("Checking 1 token").assertIsDisplayed()
    }

    @Test
    fun aTokenRowIsHiddenFromItsMenuAndAnAddedImpostorIsMarked() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true),
                    AssetRow(
                        "USDC",
                        "Not USDC",
                        "5 USDC",
                        null,
                        verified = true,
                        contract = contract,
                        warning = "Not the built-in USDC: another contract",
                    ),
                ),
            ),
        )

        rule.onNodeWithText("Not the built-in USDC: another contract").assertIsDisplayed()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Hide token").performClick()
        assertEquals(listOf("hide USDC $contract"), events)
    }

    @Test
    fun howHidingWentIsShownInASnackbar() {
        val said = "USDC could not be hidden: the token settings cannot be saved right now."
        show(ready.copy(message = said))

        rule.onNodeWithText(said).assertIsDisplayed()
    }

    @Test
    fun aSafesOwnersAreBehindTheirSegmentWithTheOnesHeldHereMarked() {
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                safe = SafeDetails.Shown(
                    title = "Safe 1.4.1 · nonce 12",
                    signers = "2 of 2 owners must sign",
                    owners = listOf(
                        OwnerRow("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", "Daily", heldHere = true),
                        OwnerRow("0x3333333333333333333333333333333333333333", null, heldHere = false),
                    ),
                    modules = listOf("0x4444444444444444444444444444444444444444"),
                    guard = null,
                    checkedAt = "Verified at block 9,412,118",
                ),
                segment = DetailSegment.OWNERS,
            ),
        )

        rule.onNodeWithText("2 of 2 owners must sign").assertIsDisplayed()
        rule.onNodeWithText("This device").assertIsDisplayed()
        rule.onNodeWithText("Not in this wallet").assertIsDisplayed()
        rule.onNodeWithText("Modules: each can move this Safe's funds without the owners' signatures.").assertExists()
        rule.onNodeWithText("Assets").performClick()
        assertEquals(listOf("segment ASSETS"), events)
    }
}
