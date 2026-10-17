package eth.serotine.ui.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [WatchScreen] headless. */
class WatchScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private var adds = 0

    private fun show(state: WatchUiState) = rule.setContent {
        SerotineTheme { WatchScreen(state, {}, {}, onAdd = { adds++ }, onBack = {}) }
    }

    @Test
    fun aVerifiedAccountShowsItsCardAndCanBeAdded() {
        show(
            WatchUiState(address = vitalik, detection = Detection.Account(vitalik, "Checked at block 9,412,118", null)),
        )

        rule.onNodeWithText("Account").assertIsDisplayed()
        rule.onNodeWithText("Checked at block 9,412,118").assertIsDisplayed()
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals(1, adds)
    }

    @Test
    fun aDelegationNamesItsDelegate() {
        val delegate = "0x63c0c19a282a1b52b07dd5a65b58948a07dae32B"
        show(WatchUiState(address = vitalik, detection = Detection.Account(vitalik, "Checked at block 7", delegate)))

        rule.onNodeWithText("Account with a delegation").assertIsDisplayed()
        rule.onNodeWithText("Its code delegates to 0x63c0…e32B (EIP-7702), which can act for it.").assertIsDisplayed()
    }

    @Test
    fun aContractCannotBeAdded() {
        show(WatchUiState(address = vitalik, detection = Detection.Contract("This address holds a contract.")))

        rule.onNodeWithText("This address holds a contract.").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun anAddressErrorReplacesTheHint() {
        val error = "Not an address: an address is 0x and 40 hexadecimal digits."
        show(WatchUiState(address = "0xzz", addressError = error))

        rule.onNodeWithText("Not an address: an address is 0x and 40 hexadecimal digits.").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()
    }
}
