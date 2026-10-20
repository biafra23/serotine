package eth.serotine.ui.watch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.verifiedStripSample
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
        SerotineTheme { WatchScreen(state, verifiedStripSample, {}, {}, onAdd = { adds++ }, onBack = {}) }
    }

    @Test
    fun aVerifiedAccountShowsItsCardAndCanBeAdded() {
        show(
            WatchUiState(address = vitalik, detection = Detection.Account(vitalik, "Checked at block 9,412,118", null)),
        )

        rule.onNodeWithText("Account").assertIsDisplayed()
        rule.onNodeWithText("Checked at block 9,412,118").assertIsDisplayed()
        rule.onNodeWithTag(WATCH_CHECKSUMMED_TAG).assertTextEquals(vitalik)
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
    fun theOtherCardsSayWhyThereIsNoAdd() {
        var detection by mutableStateOf<Detection>(Detection.Checking)
        rule.setContent {
            SerotineTheme {
                WatchScreen(WatchUiState(address = vitalik, detection = detection), verifiedStripSample, {}, {}, {}, {})
            }
        }

        rule.onNodeWithText("Checking the address on chain…").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()
        detection = Detection.InWallet("Daily")
        rule.onNodeWithText("Already in your wallet as “Daily”.").assertIsDisplayed()
        detection = Detection.Unavailable("The light client is not synced yet.")
        rule.onNodeWithText("The light client is not synced yet.").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun whileAddingTheFormIsLockedAndAnErrorIsShown() {
        show(
            WatchUiState(
                address = vitalik,
                detection = Detection.Account(vitalik, "Checked at block 7", null),
                adding = true,
                error = "Something went wrong.",
            ),
        )

        rule.onNodeWithTag(WATCH_ADDRESS_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(WATCH_NAME_TAG).assertIsNotEnabled()
        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithText("Something went wrong.").assertIsDisplayed()
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

    @Test
    fun aVerifiedSafeShowsWhoMustSignWhatActsWithoutThemAndCanBeAdded() {
        show(
            WatchUiState(
                address = vitalik,
                detection = Detection.Safe(
                    address = vitalik,
                    title = "Safe 1.5.0",
                    signers = "2 of 3 owners",
                    owners = listOf(
                        "0x1111111111111111111111111111111111111111",
                        "0x2222222222222222222222222222222222222222",
                        "0x3333333333333333333333333333333333333333",
                    ),
                    modules = listOf("0x4444444444444444444444444444444444444444"),
                    guard = "0x5555555555555555555555555555555555555555",
                    checkedAt = "Checked at block 9,412,118",
                    control = Control.CO_SIGNER,
                    moduleGuard = "0x6666666666666666666666666666666666666666",
                    threshold = "2/3",
                ),
            ),
        )

        rule.onNodeWithText("Safe 1.5.0 · 2 of 3 owners").assertIsDisplayed()
        rule.onNodeWithText("2/3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 module is enabled: it can move this Safe's funds without the owners' signatures.")
            .assertExists()
        rule.onNodeWithText("A guard checks every transaction the owners sign:").assertExists()
        rule.onNodeWithText("A module guard checks the modules' transactions:").assertExists()
        rule.onNodeWithText("Co-signer").assertExists()
        rule.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals(1, adds)
    }
}
