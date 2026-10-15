package eth.serotine.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [HomeScreen] headless (skiko software rendering) for each state. */
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun emptyStateInvitesTheFirstAccount() {
        rule.setContent { SerotineTheme { HomeScreen(HomeUiState.Ready(emptyList()), onAddAccount = {}) } }

        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithTag(HOME_ACCOUNT_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun readyStateListsEveryAccount() {
        val accounts = listOf(
            AccountItem("Daily", "0x" + "aa".repeat(20), AccountKind.EOA),
            AccountItem("Treasury", "0x" + "bb".repeat(20), AccountKind.SAFE),
        )
        rule.setContent { SerotineTheme { HomeScreen(HomeUiState.Ready(accounts), onAddAccount = {}) } }

        rule.onNodeWithTag(HOME_ACCOUNT_LIST_TAG).assertIsDisplayed()
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithText("Treasury").assertIsDisplayed()
        rule.onNodeWithText("0xbbbb…bbbb").assertIsDisplayed()
        rule.onNodeWithText("Safe").assertIsDisplayed()
    }

    @Test
    fun titleAndVersionAreOneMergedSemanticsNode() {
        rule.setContent { SerotineTheme { HomeScreen(HomeUiState.Loading, onAddAccount = {}) } }

        // Exactly one node carries BOTH strings — proof they merged rather than merely
        // both being on screen. The merged tree is what accessibility services read.
        rule.onAllNodes(hasText("Serotine") and hasText("v$APP_VERSION")).assertCountEquals(1)
    }

    @Test
    fun theTopBarNamesTheOneNetworkTheAppUses() {
        rule.setContent { SerotineTheme { HomeScreen(HomeUiState.Loading, onAddAccount = {}) } }

        rule.onNodeWithTag(HOME_NETWORK_TAG).assertTextEquals("Sepolia")
    }

    @Test
    fun theFloatingActionButtonAsksToAddAnAccount() {
        var clicks = 0
        rule.setContent { SerotineTheme { HomeScreen(HomeUiState.Ready(emptyList()), onAddAccount = { clicks++ }) } }

        rule.onNodeWithContentDescription("Add account").performClick()

        assertEquals(1, clicks)
    }
}
