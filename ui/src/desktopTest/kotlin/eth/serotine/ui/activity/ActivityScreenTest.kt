package eth.serotine.ui.activity

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless Activity screen headless. */
class ActivityScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val row = ActivityRow(
        key = "0x" + "1".repeat(64),
        title = "Sent 0.25 ETH",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "Reverted in block 9,401,220",
        state = ActivityState.FAILED,
        opens = "0x" + "1".repeat(64),
    )

    private fun show(state: ActivityUiState) = rule.setContent {
        SerotineTheme { ActivityScreen(state, onBack = {}, onOpen = { opened += it }) }
    }

    @Test
    fun aRowSaysWhatItDidAndHowItEndedAndATapOpensItsStatus() {
        show(ActivityUiState.Shown("Alice", listOf(row), "Transactions sent from this device."))

        rule.onNodeWithText("Transactions sent from this device.").assertIsDisplayed()
        rule.onNodeWithText("Reverted in block 9,401,220").assertIsDisplayed()
        rule.onNodeWithText("To Bob · 0x5aAe…eAed").assertIsDisplayed()
        rule.onNodeWithText("Sent 0.25 ETH").performClick()

        assertEquals(listOf(row.key), opened)
    }

    @Test
    fun aSafesEventOpensNothing() {
        val event = row.copy(key = "0x" + "2".repeat(64) + ":0", title = "Received 1 ETH", opens = null)
        show(ActivityUiState.Shown("Treasury", listOf(event), "Its events on chain."))

        rule.onNodeWithText("Received 1 ETH").performClick()

        assertEquals(emptyList(), opened)
    }

    @Test
    fun nothingSentSaysSo() {
        show(ActivityUiState.Shown("Alice", emptyList(), "Transactions sent from this device."))

        rule.onNodeWithText("Nothing sent from this device yet.").assertIsDisplayed()
        rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun anUnreadableHistorySaysWhy() {
        show(ActivityUiState.Unavailable("Alice", "The history cannot be read right now."))

        rule.onNodeWithText("The history cannot be read right now.").assertIsDisplayed()
    }
}
