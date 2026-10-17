package eth.serotine.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UPGRADE_BANNER_TAG
import eth.serotine.ui.update.UpgradeBannerItem
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [HomeScreen] headless (skiko software rendering) for each state. */
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val syncing = LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true)

    private fun show(state: HomeUiState, onAddAccount: () -> Unit = {}) =
        rule.setContent { SerotineTheme { HomeScreen(state, onAddAccount = onAddAccount) } }

    @Test
    fun emptyStateInvitesTheFirstAccount() {
        show(HomeUiState.Ready("Sepolia", syncing, emptyList()))

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithTag(HOME_ACCOUNT_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun readyStateListsEveryAccount() {
        val accounts = listOf(
            AccountItem("Daily", "0x" + "aa".repeat(20), AccountKind.EOA, "0.5 ETH", true),
            AccountItem("Treasury", "0x" + "bb".repeat(20), AccountKind.SAFE, "Syncing…", false),
        )
        show(HomeUiState.Ready("Sepolia", syncing, accounts))

        rule.onNodeWithTag(HOME_ACCOUNT_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("Treasury").assertIsDisplayed()
        rule.onNodeWithText("0xbbbb…bbbb · Safe").assertIsDisplayed()
        rule.onNodeWithText("0.5 ETH").assertIsDisplayed()
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test
    fun titleAndVersionAreOneMergedSemanticsNode() {
        show(HomeUiState.Loading("Sepolia", syncing))

        // Exactly one node carries BOTH strings — proof they merged rather than merely
        // both being on screen. The merged tree is what accessibility services read.
        rule.onAllNodes(hasText("Serotine") and hasText("v$APP_VERSION")).assertCountEquals(1)
    }

    @Test
    fun theTopBarNamesTheNetworkFromItsState() {
        show(HomeUiState.Loading("Sepolia", syncing))

        rule.onNodeWithTag(HOME_NETWORK_TAG).assertTextEquals("Sepolia")
    }

    @Test
    fun anUpgradeBannerIsShownUnderTheStripWithTheWayToTheUpdate() {
        val banner = UpgradeBannerItem(
            "Network upgrade ahead: update Serotine",
            "Peers announce a Sepolia network upgrade on Nov 6, 2026 that this version does not support.",
            "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
            urgent = false,
        )
        show(HomeUiState.Ready("Sepolia", syncing, emptyList(), upgrade = banner))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertIsDisplayed()
        rule.onNodeWithText(banner.text).assertIsDisplayed()
        rule.onNodeWithText(banner.details).assertIsDisplayed()
        rule.onNodeWithText("Get the update").assertIsDisplayed()
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
    }

    @Test
    fun noUpgradeNoBanner() {
        show(HomeUiState.Ready("Sepolia", syncing, emptyList()))

        rule.onNodeWithTag(UPGRADE_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun aSetAsideNoticeIsShownAboveTheList() {
        val notice = "Your saved account list was damaged and has been set aside. Add your accounts again."
        show(HomeUiState.Ready("Sepolia", syncing, emptyList(), notice))

        rule.onNodeWithTag(HOME_NOTICE_TAG).assertTextEquals(notice)
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add account").assertExists()
    }

    @Test
    fun anUnavailableListIsExplainedWithoutInvitingAnAdd() {
        val message = "Your saved account list cannot be read right now."
        show(HomeUiState.ListUnavailable("Sepolia", syncing, message))

        rule.onNodeWithTag(HOME_NOTICE_TAG).assertTextEquals(message)
        rule.onNodeWithText("No accounts yet").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add account").assertDoesNotExist()
    }

    @Test
    fun theStatusStripShowsWhatTheLightClientIsDoing() {
        show(HomeUiState.Loading("Sepolia", syncing))

        rule.onNodeWithText("Verifying sync committees · 1 of 4 periods").assertIsDisplayed()
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
    }

    @Test
    fun theFloatingActionButtonAsksToAddAnAccount() {
        var clicks = 0
        show(HomeUiState.Ready("Sepolia", syncing, emptyList()), onAddAccount = { clicks++ })

        rule.onNodeWithContentDescription("Add account").performClick()

        assertEquals(1, clicks)
    }
}
