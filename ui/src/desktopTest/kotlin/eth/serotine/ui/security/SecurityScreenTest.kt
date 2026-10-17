package eth.serotine.ui.security

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SecurityScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    private val off = SecurityUiState.Ready(
        password = "Set",
        protection = "Kept under your wallet password and this device's secure hardware.",
        biometrics = BiometricRow("Off: signing asks for the wallet password.", on = false, canChange = true),
    )

    private fun show(state: SecurityUiState) = rule.setContent {
        SerotineTheme {
            SecurityScreen(
                state,
                onBiometricsChange = { events += "biometrics $it" },
                onPasswordChange = { events += "password $it" },
                onConfirm = { events += "confirm" },
                onCancel = { events += "cancel" },
                onBack = {},
            )
        }
    }

    @Test
    fun theSwitchAsksToTurnBiometricUnlockOn() {
        show(off)

        rule.onNodeWithText("Off: signing asks for the wallet password.").assertIsDisplayed()
        rule.onNodeWithTag(SECURITY_BIOMETRIC_SWITCH_TAG).assertIsOff().performClick()
        assertEquals(listOf("biometrics true"), events)
    }

    @Test
    fun turningItOnAsksForTheWalletPasswordHereAndSaysCaptureIsNotBlocked() {
        show(off.copy(enabling = PasswordPrompt()))

        rule.onNodeWithText("Turn on").assertIsNotEnabled()
        rule.onNodeWithTag(SECURITY_PASSWORD_TAG).performTextInput("correct horse battery")
        assertEquals("password correct horse battery", events.first())
        rule.onNodeWithText("Screenshots and screen recordings of this window cannot", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aTypedPasswordCanBeConfirmedOrTheWholeThingCancelled() {
        show(off.copy(enabling = PasswordPrompt(WalletPassword("correct horse battery"))))

        rule.onNodeWithText("Turn on").assertIsEnabled().performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("confirm", "cancel"), events)
    }

    @Test
    fun anOnSwitchTurnsItOffAndAnUnavailableOneDoesNotMove() {
        show(off.copy(biometrics = BiometricRow("On.", on = true, canChange = true)))
        rule.onNodeWithTag(SECURITY_BIOMETRIC_SWITCH_TAG).assertIsOn().performClick()
        assertEquals(listOf("biometrics false"), events)
    }

    @Test
    fun aSwitchThatCannotMoveIsDisabled() {
        show(off.copy(biometrics = BiometricRow("Not on this device yet.", on = false, canChange = false)))

        rule.onNodeWithTag(SECURITY_BIOMETRIC_SWITCH_TAG).assertIsNotEnabled()
    }
}
