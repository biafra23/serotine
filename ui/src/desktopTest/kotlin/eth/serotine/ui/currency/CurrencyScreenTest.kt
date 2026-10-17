package eth.serotine.ui.currency

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [CurrencyScreen] headless. */
class CurrencyScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val chosen = mutableListOf<String>()

    private fun show(state: CurrencyUiState) = rule.setContent {
        SerotineTheme { CurrencyScreen(state, onChoose = { chosen += it }, onBack = {}) }
    }

    private val options = listOf(
        CurrencyOption("USD", "US dollar", chosen = true),
        CurrencyOption("EUR", "Euro", chosen = false),
    )

    @Test
    fun theChosenCurrencyIsSelectedAndAnotherIsChosenByATap() {
        show(CurrencyUiState(options))

        rule.onNodeWithText("USD").assertIsSelected()
        rule.onNodeWithText("Euro").performClick()
        assertEquals(listOf("EUR"), chosen)
    }

    @Test
    fun nothingIsChosenWhileTheChoiceCannotBeRead() {
        show(CurrencyUiState(options, notice = "Your choice of currency cannot be read right now.", canChoose = false))

        rule.onNodeWithText("Your choice of currency cannot be read right now.").assertExists()
        rule.onNodeWithText("EUR").assertIsNotEnabled()
    }
}
