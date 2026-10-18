package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class AddressLineTest {

    @get:Rule
    val rule = createComposeRule()

    private val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    @Test
    fun aLongLabelWrapsAndTheAddressKeepsItsLine() {
        rule.setContent {
            SerotineTheme {
                // A phone's width, less the card's padding.
                Column(Modifier.width(320.dp)) {
                    AddressLine(address)
                    AddressLine(address, "A module guard checks the modules' transactions:")
                }
            }
        }

        val lines = rule.onAllNodesWithText(shortAddress(address))
        val alone = lines[0].getUnclippedBoundsInRoot()
        val labelled = lines[1].getUnclippedBoundsInRoot()
        assertEquals(alone.width, labelled.width, "the address is not squeezed by its label")
        assertEquals(alone.height, labelled.height, "one line, as without a label")
    }
}
