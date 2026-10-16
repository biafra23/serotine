package eth.serotine.ui.renameaccount

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.ui.removeaccount.RemoveAccountDialog
import eth.serotine.ui.removeaccount.RemoveAccountUiState
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless rename and remove dialogs headless. */
class AccountDialogsTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()

    @Test
    fun renameShowsTheNameAndItsError() {
        rule.setContent {
            SerotineTheme {
                RenameAccountDialog(
                    RenameAccountUiState.Editing("", error = "Enter a name"),
                    onNameChange = { events += "type $it" },
                    onSave = { events += "save" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithText("Enter a name").assertIsDisplayed()
        rule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("S")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Cancel").performClick()

        // A field whose state never changes (nothing holds it here) may report its reset too.
        assertEquals("type S", events.first())
        assertEquals(listOf("save", "cancel"), events.filterNot { it.startsWith("type") })
    }

    @Test
    fun renameCannotSaveTwice() {
        rule.setContent {
            SerotineTheme { RenameAccountDialog(RenameAccountUiState.Editing("Daily", saving = true), {}, {}, {}) }
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun removingAnAccountWithAKeySaysTheKeyGoesToo() {
        rule.setContent {
            SerotineTheme {
                RemoveAccountDialog(
                    RemoveAccountUiState.Confirm("Daily", keyed = true),
                    onRemove = { events += "remove" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithText("Remove Daily?").assertIsDisplayed()
        rule.onNodeWithText("Only its recovery phrase brings this account back", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf("remove"), events)
    }

    @Test
    fun removingAWatchedAccountSaysItCanBeWatchedAgain() {
        rule.setContent {
            SerotineTheme { RemoveAccountDialog(RemoveAccountUiState.Confirm("Savings", keyed = false), {}, {}) }
        }

        rule.onNodeWithText("You can watch it again at any time", substring = true).assertIsDisplayed()
    }
}
