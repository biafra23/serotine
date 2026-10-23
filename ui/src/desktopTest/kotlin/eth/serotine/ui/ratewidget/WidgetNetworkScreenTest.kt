package eth.serotine.ui.ratewidget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class WidgetNetworkScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aTappedNetworkIsChosenAndCancelPlacesNone() {
        val events = mutableListOf<String>()
        rule.setContent {
            SerotineTheme {
                WidgetNetworkScreen(
                    WidgetNetworkUiState(
                        listOf(NetworkLabel(1, "Ethereum", false), NetworkLabel(11_155_111, "Sepolia", true)),
                    ),
                    onChoose = { events += "chose $it" },
                    onCancel = { events += "cancel" },
                )
            }
        }

        rule.onNodeWithTag(WIDGET_NETWORK_TAG + 11_155_111).performClick()
        rule.onNodeWithContentDescription("Cancel").performClick()

        assertEquals(listOf("chose 11155111", "cancel"), events)
    }
}
