package eth.serotine.ui.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SimpleWatchScreen] headless. */
class SimpleWatchScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"

    private fun show(state: WatchUiState) = rule.setContent {
        SerotineTheme {
            SimpleWatchScreen(state = state, onAddressChange = {}, onNameChange = {}, onAdd = {}, onBack = {})
        }
    }

    @Test
    fun theCardAndTheResolvedNameSayCheckedOnChainAndNameNoBlock() {
        show(
            WatchUiState(
                address = "savings-example.eth",
                addressNote = "Resolves to $savings, verified at finalized block 8,412,240",
                simpleAddressNote = "Resolves to $savings, checked on chain",
                detection = Detection.Account(savings, "Checked at block 8,412,307", null, "Checked on chain"),
            ),
        )

        rule.onNodeWithText("Resolves to $savings, checked on chain").assertIsDisplayed()
        rule.onNodeWithText("Checked on chain").assertIsDisplayed()
        rule.onNodeWithText("block", substring = true).assertDoesNotExist()

        rule.onNodeWithTag(WATCH_DETAILS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Checked at block 8,412,307").performScrollTo().assertIsDisplayed()
    }
}
