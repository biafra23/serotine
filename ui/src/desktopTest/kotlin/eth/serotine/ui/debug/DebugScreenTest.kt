package eth.serotine.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.LogLevel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [DebugScreen] headless. */
class DebugScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val levels = mutableListOf<LogLevel>()
    private val filters = mutableListOf<String>()
    private var cleared = 0

    private val line = DebugLogLine(1, "14:03:27.481", LogLevel.INFO, "Myotis · requestAccount: ok, 34 ms")

    private fun show(state: DebugUiState) = rule.setContent {
        SerotineTheme {
            DebugScreen(state, onLevel = { levels += it }, onFilter = { filters += it }, onClear = { cleared++ })
        }
    }

    @Test
    fun showsEachLineWithItsTimeAndLevelAndHowManyThereAre() {
        show(DebugUiState(lines = listOf(line), recorded = 1))

        rule.onNodeWithTag(DEBUG_LOG_TAG).assertIsDisplayed()
        rule.onNodeWithText("14:03:27.481  INFO").assertIsDisplayed()
        rule.onNodeWithText("Myotis · requestAccount: ok, 34 ms").assertIsDisplayed()
        rule.onNodeWithText("1 records").assertIsDisplayed()
    }

    @Test
    fun theLevelTheFilterAndClearAreAskedFor() {
        show(DebugUiState(lines = listOf(line), recorded = 1))

        rule.onNodeWithText("DEBUG").performClick()
        rule.onNodeWithText("Filter").performTextInput("status")
        rule.onNodeWithContentDescription("Clear the log").performClick()

        assertEquals(listOf(LogLevel.DEBUG), levels)
        // The first change typed; a field whose state never takes it may report its own value again.
        assertEquals("status", filters.first())
        assertEquals(1, cleared)
    }

    @Test
    fun anEmptyLogSaysSoAndCanStillBeCleared() {
        show(DebugUiState())

        rule.onNodeWithText("Nothing recorded yet.").assertIsDisplayed()
        // The log may hold records at a level not shown: clearing stays possible.
        rule.onNodeWithContentDescription("Clear the log").assertIsEnabled().performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun atTheTopANewerLineComesIntoViewAsItArrives() {
        val lines = (60L downTo 1L).map { DebugLogLine(it, "14:03:27.481", LogLevel.INFO, "Myotis · request $it") }
        var state by mutableStateOf(DebugUiState(lines = lines, recorded = lines.size))
        rule.setContent {
            SerotineTheme { DebugScreen(state, onLevel = {}, onFilter = {}, onClear = {}) }
        }
        rule.onNodeWithText("Myotis · request 60").assertIsDisplayed()

        val newer = DebugLogLine(61, "14:03:28.002", LogLevel.INFO, "Myotis · request 61")
        state = state.copy(lines = listOf(newer) + lines, recorded = lines.size + 1)

        rule.onNodeWithText("Myotis · request 61").assertIsDisplayed()
    }

    @Test
    fun aFilterThatMatchesNothingSaysSo() {
        show(DebugUiState(filter = "sendRaw", recorded = 3))

        rule.onNodeWithText("No record contains “sendRaw”.").assertIsDisplayed()
        rule.onNodeWithText("0 of 3 records").assertIsDisplayed()
        rule.onNodeWithContentDescription("Clear the filter").performClick()
        assertEquals(listOf(""), filters)
    }
}
