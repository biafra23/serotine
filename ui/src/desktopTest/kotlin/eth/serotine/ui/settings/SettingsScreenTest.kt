package eth.serotine.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
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
    private val screenCaptureChanges = mutableListOf<Boolean>()
    private val debugScreenChanges = mutableListOf<Boolean>()

    private fun show(screenCaptureAllowed: Boolean? = null, debugScreen: Boolean = false) = rule.setContent {
        SerotineTheme {
            val lightClient = LightClientItem("Verified at block 9,412,118 · 8 s ago", null, false, VERIFIED)
            SettingsScreen(
                SettingsUiState(
                    "Sepolia",
                    lightClient,
                    APP_VERSION,
                    tokens = "3 built in · 1 added",
                    currency = "EUR",
                    screenCaptureAllowed = screenCaptureAllowed,
                    debugScreen = debugScreen,
                ),
                onCurrency = { currencyOpened++ },
                onTokens = { tokensOpened++ },
                onSecurity = { securityOpened++ },
                onScreenCaptureChange = { screenCaptureChanges += it },
                onDebugScreenChange = { debugScreenChanges += it },
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
    fun aReleaseBuildHasNoScreenRecordingRow() {
        show()

        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).assertDoesNotExist()
        rule.onNodeWithText("Debug build").assertDoesNotExist()
    }

    @Test
    fun theDebugBuildsRowShowsTheSwitchAndTogglesIt() {
        show(screenCaptureAllowed = false)

        rule.onNodeWithText("Allow screen recording").assertExists()
        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).assertIsOff().performClick()
        assertEquals(listOf(true), screenCaptureChanges)
    }

    @Test
    fun theDebugBuildsRowSaysWhenCaptureIsAllowed() {
        show(screenCaptureAllowed = true)

        rule.onNodeWithTag(SETTINGS_SCREEN_CAPTURE_TAG).assertIsOn().performClick()
        assertEquals(listOf(false), screenCaptureChanges)
    }

    @Test
    fun theDebugTabsRowTurnsItOnAndOff() {
        show()

        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), debugScreenChanges)
    }

    @Test
    fun theDebugTabsRowSaysWhenItIsOn() {
        show(debugScreen = true)

        rule.onNodeWithTag(SETTINGS_DEBUG_SCREEN_TAG).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(false), debugScreenChanges)
    }
}
