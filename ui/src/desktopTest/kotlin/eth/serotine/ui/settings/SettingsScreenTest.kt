package eth.serotine.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SettingsScreen] headless. */
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var tokensOpened = 0
    private var currencyOpened = 0
    private var securityOpened = 0
    private val meteredChoices = mutableListOf<Boolean>()

    private fun show(metered: MeteredDownloadsItem? = MeteredDownloadsItem.NOT_ALLOWED) = rule.setContent {
        SerotineTheme {
            val lightClient = LightClientItem("Verified at block 9,412,118 · 8 s ago", null, false, VERIFIED)
            SettingsScreen(
                SettingsUiState(
                    "Sepolia",
                    lightClient,
                    APP_VERSION,
                    tokens = "3 built in · 1 added",
                    currency = "EUR",
                    meteredDownloads = metered,
                ),
                onCurrency = { currencyOpened++ },
                onTokens = { tokensOpened++ },
                onSecurity = { securityOpened++ },
                onMeteredDownloads = { meteredChoices += it },
            )
        }
    }

    @Test
    fun theTokensRowSaysWhatTheWalletKnowsAndOpensTheList() {
        show()

        rule.onNodeWithText("3 built in · 1 added").performClick()
        assertEquals(1, tokensOpened)
    }

    @Test
    fun theCurrencyRowSaysTheChoiceAndOpensThePicker() {
        show()

        rule.onNodeWithText("EUR").performClick()
        assertEquals(1, currencyOpened)
    }

    @Test
    fun theSecurityRowOpensThePasswordAndBiometricSettings() {
        show()

        rule.onNodeWithText("Wallet password · Biometric unlock").performClick()
        assertEquals(1, securityOpened)
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

    @Test
    fun theMeteredDownloadsSwitchIsOffByDefaultAndATapOnItsRowAllowsThem() {
        show()

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertIsOff()
        rule.onNodeWithText(METERED_DOWNLOADS_OFF).performClick()
        assertEquals(listOf(true), meteredChoices)
    }

    @Test
    fun aTapOnTheAllowedSwitchTurnsItOff() {
        show(MeteredDownloadsItem(on = true, text = METERED_DOWNLOADS_ON))

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertIsOn()
        rule.onNodeWithText(METERED_DOWNLOADS_ON).performClick()
        assertEquals(listOf(false), meteredChoices)
    }

    @Test
    fun theMeteredDownloadsSwitchTakesNoTapUntilItsChoiceIsRead() {
        show(metered = null)

        rule.onNodeWithTag(SETTINGS_METERED_TAG).assertHasNoClickAction()
        rule.onNodeWithText("Heavy downloads on metered connections").performClick()
        assertEquals(emptyList(), meteredChoices)
        rule.onAllNodesWithTag(UNAVAILABLE_REASON_TAG).assertCountEquals(0)
    }

    @Test
    fun aGreyedMeteredDownloadsSwitchSaysWhyOnATapAndChangesNothing() {
        show(MeteredDownloadsItem(on = false, text = "Not reported here", unavailableReason = "No metering here."))

        rule.onNodeWithTag(SETTINGS_METERED_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
        rule.onNodeWithText("Not reported here").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals("No metering here.")
        assertEquals(emptyList(), meteredChoices)
    }
}
