package eth.serotine.ui.accountdetail

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
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
                onSend = { events += "send $it" },
                onSwap = { events += "swap $it" },
                onReceive = { events += "receive" },
                onCopy = { events += "copy $it" },
                onRename = { events += "rename" },
                onRemove = { events += "remove" },
                onHideToken = { contract, symbol -> events += "hide $symbol $contract" },
                onMessageShown = { events += "message shown" },
                onSort = { events += "sort $it" },
                onSegment = { events += "segment $it" },
                onOpenSafeTx = { events += "open $it" },
                onImportRequest = { events += "import" },
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
    fun anAccountWhoseKeyIsHeldHereSendsFromItsButtonAndFromAnAssetsMenu() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                control = Control.FULL_CONTROL,
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true),
                    AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = contract),
                ),
            ),
        )

        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Send USDC").performClick()
        rule.onNodeWithText("1.5 ETH").performTouchInput { longClick() }
        rule.onNodeWithText("Send ETH").performClick()

        assertEquals(listOf("send null", "send $contract", "send null"), events)
    }

    @Test
    fun aWatchOnlyAccountShowsSendGreyedOutAndATapSaysWhy() {
        show(ready.copy(assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = "0x1"))))

        // Owner ruling, 2026-10-18, #55: shown, greyed out, and a tap says what is missing.
        rule.onNodeWithText("Send").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so nothing can be sent from it. Import its " +
                "recovery phrase to send from it.",
        )
        rule.onNodeWithText("Close").performClick()
        assertEquals(emptyList(), events.filter { it.startsWith("send") }, "nothing sent")
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Hide token").assertIsDisplayed()
        rule.onNodeWithText("Send USDC")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        assertEquals(emptyList(), events.filter { it.startsWith("send") }, "nothing sent")
    }

    @Test
    fun anAccountWhoseKeyIsHeldHereSwapsFromItsButtonAndFromAnAssetsMenu() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                control = Control.FULL_CONTROL,
                assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = contract)),
            ),
        )

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG).performClick()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Swap USDC").performClick()

        assertEquals(listOf("swap null", "swap $contract"), events)
    }

    @Test
    fun aWatchOnlyAccountShowsSwapGreyedOutAndATapSaysWhy() {
        show(ready.copy(assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = "0x1"))))

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it. " +
                "Import its recovery phrase to swap from it.",
        )
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Swap USDC").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        assertEquals(emptyList(), events.filter { it.startsWith("swap") }, "nothing swapped")
    }

    @Test
    fun aSafeSwapsWithAnOwnerHeldHereAndIsGreyedOutWithNone() {
        show(ready.copy(kind = AccountKind.SAFE, control = Control.CO_SIGNER, safe = SafeDetails.Checking))
        rule.onNodeWithTag(ACCOUNT_SWAP_TAG).performClick()
        assertEquals(listOf("swap null"), events.filter { it.startsWith("swap") })
    }

    @Test
    fun aWatchedSafesSwapIsGreyedOutAndATapSaysSo() {
        show(ready.copy(kind = AccountKind.SAFE, control = Control.WATCH_ONLY, safe = SafeDetails.Checking))

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "No owner of this Safe has its key on this device, so nothing can be proposed from it. Import an " +
                "owner's recovery phrase to propose and sign its transactions.",
        )
        assertEquals(emptyList(), events.filter { it.startsWith("swap") }, "nothing swapped")
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

    @Test
    fun aSafesQueueListsItsTransactionsOpensOneAndImportsARequest() {
        val hash = "0x" + "3c".repeat(32)
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                control = Control.CO_SIGNER,
                safe = SafeDetails.Checking,
                segment = DetailSegment.QUEUE,
                queue = SafeQueue.Shown(
                    listOf(
                        QueueRow(hash, "Send 40 USDC", null, "Nonce 12 · next", "1 of 2 signatures"),
                        QueueRow(
                            "0x" + "4d".repeat(32),
                            "Delegate call",
                            null,
                            "Nonce 13 · 1 before it",
                            "0 of 2 signatures",
                            refusal = "Not signed here: a delegate call",
                        ),
                    ),
                ),
            ),
        )

        rule.onNodeWithText("Queue (2)").assertIsDisplayed()
        rule.onAllNodesWithTag(ACCOUNT_QUEUE_ROW_TAG).assertCountEquals(2)
        rule.onNodeWithText("Nonce 12 · next · 1 of 2 signatures").assertIsDisplayed()
        rule.onNodeWithText("Not signed here: a delegate call").assertIsDisplayed()
        rule.onNodeWithText("Send 40 USDC").performClick()
        rule.onNodeWithText("Import a request").performClick()
        // A Safe with an owner held here sends: Send proposes a Safe transaction.
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf("open $hash", "import", "send null"), events)
    }

    @Test
    fun aQueueThatCannotBeReadSaysSo() {
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                safe = SafeDetails.Checking,
                segment = DetailSegment.QUEUE,
                queue = SafeQueue.Unavailable("This device's queue of Safe transactions cannot be read right now."),
            ),
        )

        rule.onNodeWithText("This device's queue of Safe transactions cannot be read right now.").assertIsDisplayed()
        rule.onNodeWithText("Queue").assertIsDisplayed()
        rule.onNodeWithText("Send")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
    }
}
