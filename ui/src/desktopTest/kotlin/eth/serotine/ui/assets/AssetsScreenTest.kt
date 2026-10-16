package eth.serotine.ui.assets

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
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

    private fun show(state: AssetsUiState, onAddAccount: () -> Unit = {}) =
        rule.setContent {
            SerotineTheme { AssetsScreen(state, onAddAccount = onAddAccount, onAccountAction = { actions += it }) }
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
    fun theFloatingActionButtonAsksToAddAnAccount() {
        var clicks = 0
        show(AssetsUiState.Ready("Sepolia", syncing, emptyList()), onAddAccount = { clicks++ })

        rule.onNodeWithContentDescription("Add account").performClick()

        assertEquals(1, clicks)
    }
}
