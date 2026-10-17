package eth.serotine.ui.assets

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UPGRADE_BANNER_TAG
import eth.serotine.ui.update.UpgradeBannerItem
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [AssetsScreen] headless (skiko software rendering) for each state. */
class AssetsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val syncing = LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true)
    private val daily = AccountItem(
        "Daily",
        "0x8F3a2c71E5b9D04A6f1e3C2bA7d90E5f4C6b91c2",
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "0.5 ETH",
        true,
    )
    private val savings = AccountItem(
        "Savings",
        "0x3C9e1a7b5d2f8e4c6b0a9d3e7f1c5a2b8e4d6F09",
        AccountKind.EOA,
        Control.WATCH_ONLY,
        "Syncing…",
        false,
    )
    private val actions = mutableListOf<AccountAction>()

    private val adds = mutableListOf<String>()

    private fun show(state: AssetsUiState) =
        rule.setContent {
            SerotineTheme {
                AssetsScreen(
                    state,
                    onWatchAddress = { adds += "watch" },
                    onImportPhrase = { adds += "import" },
                    onCreateAccount = { adds += "create" },
                    onAccountAction = { actions += it },
                )
            }
        }

    @Test
    fun emptyStateInvitesTheFirstAccount() {
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList()))

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun readyStateListsEveryAccountWithItsControlAndBalance() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily, savings)))

        rule.onNodeWithTag(ASSETS_ACCOUNT_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("0x8F3a…91c2").assertIsDisplayed()
        rule.onNodeWithText("Full control").assertIsDisplayed()
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("0.5 ETH").assertIsDisplayed()
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test
    fun aTapOpensTheAccount() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily)))

        rule.onNodeWithText("Daily").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Open(daily.address)), actions)
    }

    @Test
    fun aLongPressOpensTheAccountMenu() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily)))

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Receive").assertIsDisplayed()
        rule.onNodeWithText("Copy address").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Remove(daily.address)), actions)
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun sendIsInTheMenuOfAnAccountWhoseKeyIsHeldHereOnly() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily, savings)))

        rule.onNodeWithText("Savings").performTouchInput { longClick() }
        rule.onNodeWithText("Receive").assertIsDisplayed()
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Send").performClick()

        assertEquals(listOf(AccountAction.Receive(savings.address), AccountAction.Send(daily.address)), actions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aRightClickOpensTheSameMenu() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily)))

        rule.onNodeWithText("Daily").performMouseInput { rightClick() }
        rule.onNodeWithText("Receive").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Receive(daily.address)), actions)
    }

    @Test
    fun copyingSaysSo() {
        show(AssetsUiState.Ready("Sepolia", syncing, listOf(daily)))

        rule.onNodeWithText("Daily").performTouchInput { longClick() }
        rule.onNodeWithText("Copy address").performClick()

        assertEquals(listOf<AccountAction>(AccountAction.Copy(daily.address)), actions)
        rule.onNodeWithText("Address copied").assertIsDisplayed()
    }

    @Test
    fun theTopBarNamesTheNetworkFromItsState() {
        show(AssetsUiState.Loading("Sepolia", syncing))

        rule.onNodeWithTag(ASSETS_NETWORK_TAG).assertTextEquals("Sepolia")
    }

    @Test
    fun anUpgradeBannerIsShownUnderTheStripWithTheWayToTheUpdate() {
        val banner = UpgradeBannerItem(
            "Network upgrade ahead: update Serotine",
            "Peers announce a Sepolia network upgrade on Nov 6, 2026 that this version does not support.",
            "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
            urgent = false,
        )
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList(), upgrade = banner))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertIsDisplayed()
        rule.onNodeWithText(banner.text).assertIsDisplayed()
        rule.onNodeWithText(banner.details).assertIsDisplayed()
        rule.onNodeWithText("Get the update").assertIsDisplayed()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun noUpgradeNoBanner() {
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList()))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun aSetAsideNoticeIsShownAboveTheList() {
        val notice = "Your saved account list was damaged and has been set aside. Add your accounts again."
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList(), notice))

        rule.onNodeWithTag(ASSETS_NOTICE_TAG).assertTextEquals(notice)
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add account").assertExists()
    }

    @Test
    fun anUnavailableListIsExplainedWithoutInvitingAnAdd() {
        val message = "Your saved account list cannot be read right now."
        show(AssetsUiState.ListUnavailable("Sepolia", syncing, message))

        rule.onNodeWithTag(ASSETS_NOTICE_TAG).assertTextEquals(message)
        rule.onNodeWithText("No accounts yet").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()
    }

    @Test
    fun theStatusStripShowsWhatTheLightClientIsDoing() {
        show(AssetsUiState.Loading("Sepolia", syncing))

        rule.onNodeWithText("Verifying sync committees · 1 of 4 periods").assertIsDisplayed()
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
    }

    @Test
    fun theFloatingActionButtonFansOutIntoWatchAndImport() {
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList()))

        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Watch address").performClick()
        rule.onNodeWithText("Import recovery phrase").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").performClick()
        rule.onNodeWithText("Import recovery phrase").performClick()

        assertEquals(listOf("watch", "import"), adds)
    }

    @Test
    fun theHeaderTotalsThePortfolioAndEachRowHasItsValue() {
        show(
            AssetsUiState.Ready(
                "Sepolia",
                syncing,
                listOf(daily.copy(value = "$1,000.00")),
                total = "$1,000.00",
                totalNote = "Testnet prices",
            ),
        )

        rule.onNodeWithTag(ASSETS_TOTAL_TAG).assertTextEquals("$1,000.00")
        rule.onNodeWithText("Testnet prices").assertIsDisplayed()
        // The header's total and the row's value.
        rule.onAllNodesWithText("$1,000.00", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun aSafesBlockieCarriesItsThresholdAndItsRowItsKind() {
        show(
            AssetsUiState.Ready(
                "Sepolia",
                syncing,
                listOf(daily.copy(kind = AccountKind.SAFE, signers = "2/3", kindNote = "Safe")),
            ),
        )

        rule.onNodeWithText("2/3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("0x8F3a…91c2 · Safe", useUnmergedTree = true).assertExists()
    }
}
