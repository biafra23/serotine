package eth.serotine.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test

/** Renders the stateless [SettingsScreen] headless. */
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show() = rule.setContent {
        SerotineTheme {
            val lightClient = LightClientItem("Verified at block 9,412,118 · 8 s ago", null, false)
            SettingsScreen(SettingsUiState("Sepolia", lightClient, APP_VERSION))
        }
    }

    @Test
    fun namesTheOnlyNetworkAndTheLightClientsState() {
        show()

        rule.onNodeWithText("Sepolia").assertIsDisplayed()
        rule.onNodeWithText("The only network for now").assertIsDisplayed()
        rule.onNodeWithText("Verified at block 9,412,118 · 8 s ago").assertIsDisplayed()
    }

    @Test
    fun theAppAndItsVersionAreOneMergedSemanticsNode() {
        show()

        // Exactly one node carries both strings: proof they merged, which is what a screen
        // reader announces.
        rule.onAllNodes(hasText("Serotine") and hasText("Version $APP_VERSION")).assertCountEquals(1)
    }
}
