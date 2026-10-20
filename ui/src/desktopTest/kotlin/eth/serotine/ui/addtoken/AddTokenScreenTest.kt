package eth.serotine.ui.addtoken

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [AddTokenScreen] headless. */
class AddTokenScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val contract = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
    private var adds = 0

    private fun show(state: AddTokenUiState) = rule.setContent {
        SerotineTheme { AddTokenScreen(state, verifiedStripSample, {}, onAdd = { adds++ }, onBack = {}) }
    }

    @Test
    fun theKeyboardsDoneAddsOnlyWhatAddTokenWould() {
        show(AddTokenUiState(address = contract, card = TokenCard.Checking))
        rule.onNodeWithTag(ADD_TOKEN_ADDRESS_TAG).performImeAction()
        assertEquals(0, adds)
    }

    @Test
    fun theKeyboardsDoneAddsTheTokenRead() {
        val card = TokenCard.Claim(contract, "EURC", "Euro Coin", 6, "Read at block 7", warning = null)
        show(AddTokenUiState(address = contract, card = card))
        rule.onNodeWithTag(ADD_TOKEN_ADDRESS_TAG).performImeAction()
        assertEquals(1, adds)
    }
}
