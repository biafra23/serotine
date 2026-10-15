package eth.serotine.ui.mnemonicimport

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.Mnemonic
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [MnemonicImportScreen] headless for each state. */
class MnemonicImportScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val twelveWords = "one two three four five six seven eight nine ten eleven twelve"

    private val typed = mutableListOf<String>()
    private var imports = 0
    private var backs = 0

    private fun show(state: MnemonicImportUiState) = rule.setContent {
        SerotineTheme {
            MnemonicImportScreen(
                state = state,
                onPhraseChange = { typed += it },
                onImport = { imports++ },
                onBack = { backs++ },
            )
        }
    }

    @Test
    fun anIncompletePhraseCannotBeSubmitted() {
        show(MnemonicImportUiState.Entering(Mnemonic("one two three")))

        rule.onNodeWithText("3 words").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun aFullPhraseCanBeSubmitted() {
        show(MnemonicImportUiState.Entering(Mnemonic(twelveWords)))

        rule.onNodeWithText("12 words").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_IMPORT_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun typingReportsTheFieldsText() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithText("No words yet").assertIsDisplayed()
        rule.onNodeWithTag(MNEMONIC_FIELD_TAG).performTextInput("abandon")
        assertEquals(listOf("abandon"), typed)
    }

    @Test
    fun aRefusalIsShownInPlaceOfTheWordCount() {
        val error = "Word 3 is not in the BIP-39 English word list."
        show(MnemonicImportUiState.Entering(Mnemonic(twelveWords), error = error))

        rule.onNodeWithText(error).assertIsDisplayed()
        rule.onNodeWithText("12 words").assertDoesNotExist()
    }

    @Test
    fun theDesktopSaysItCannotBlockScreenCapture() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithText("cannot be blocked", substring = true).assertExists()
    }

    @Test
    fun theBackArrowLeaves() {
        show(MnemonicImportUiState.Entering(Mnemonic.EMPTY))

        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
