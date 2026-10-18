package eth.serotine.ui.safeimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SafeImportScreen] headless. */
class SafeImportScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val empty = SafeImportUiState.Entering(
        "Sepolia",
        label = "Treasury",
        address = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        text = "",
    )
    private val events = mutableListOf<String>()

    private fun show(state: SafeImportUiState) = rule.setContent {
        SerotineTheme {
            SafeImportScreen(
                state,
                onTextChange = { events += "text $it" },
                onImport = { events += "import" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theRequestIsPastedForTheSafeShownAndNothingImportsUntilThen() {
        show(empty)

        rule.onNodeWithText("Treasury").assertIsDisplayed()
        rule.onNodeWithText("0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB").assertIsDisplayed()
        rule.onNodeWithText("Scanning its QR code is not built yet: paste the text.").assertIsDisplayed()
        rule.onNodeWithTag(IMPORT_REQUEST_BUTTON_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(IMPORT_REQUEST_FIELD_TAG).performTextInput("request")
        rule.onNodeWithContentDescription("Back").performClick()
        // A field whose state is not fed back may report its text more than once.
        assertEquals(listOf("text request", "back"), events.filterNot { it == "text " })
    }

    @Test
    fun aRefusalIsSaidUnderTheFieldAndImportAsksAgain() {
        show(empty.copy(text = "request", error = "This text is not a Safe signing request."))

        rule.onNodeWithText("This text is not a Safe signing request.").assertIsDisplayed()
        rule.onNodeWithTag(IMPORT_REQUEST_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(listOf("import"), events)
    }

    @Test
    fun whileItIsCheckedNothingElseCanBeAsked() {
        show(empty.copy(text = "request", importing = true))

        rule.onNodeWithText("Checking the Safe on chain…").assertIsDisplayed()
        rule.onNodeWithTag(IMPORT_REQUEST_BUTTON_TAG).assertIsNotEnabled()
    }
}
