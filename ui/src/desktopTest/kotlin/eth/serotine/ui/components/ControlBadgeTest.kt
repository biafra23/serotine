package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlBadgeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(name: String, control: Control, width: Int, direction: LayoutDirection = LayoutDirection.Ltr) =
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SerotineTheme { Column(Modifier.width(width.dp)) { NameWithControlBadge(name, control) } }
            }
        }

    /** No node tagged [tag] is placed: the form not chosen is composed but never laid out. */
    private fun assertNotShown(tag: String) = assertTrue(
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().none { it.layoutInfo.isPlaced },
        "$tag is not shown",
    )

    @Test
    fun withRoomTheBadgeShowsItsTextBesideTheName() {
        show("Daily", Control.FULL_CONTROL, width = 320)

        rule.onNodeWithTag(CONTROL_BADGE_TEXT_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertNotShown(CONTROL_BADGE_ICON_TAG)
        val name = rule.onNodeWithText("Daily").getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithText("Full control").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(badge.left > name.right, "the badge after the name, on its line")
    }

    @Test
    fun whereItsTextWouldNotFitTheBadgeShowsItsIcon() {
        // Too narrow for "Treasury" and "Co-signer" side by side: a row squeezed by its balance.
        show("Treasury", Control.CO_SIGNER, width = 110)

        rule.onNodeWithTag(CONTROL_BADGE_ICON_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertNotShown(CONTROL_BADGE_TEXT_TAG)
        val name = rule.onNodeWithText("Treasury").assertIsDisplayed().getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithTag(CONTROL_BADGE_ICON_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(badge.left > name.right, "the icon after the name, on its line")
    }

    @Test
    fun aScreenReaderHearsTheBadgeOnceInEitherForm() {
        show("Treasury", Control.CO_SIGNER, width = 110)

        assertEquals(1, rule.onAllNodesWithText("Co-signer", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Co-signer", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aNameTooLongForItsLineWrapsAndLeavesTheIconItsRoom() {
        show("Savings for the summer holidays", Control.WATCH_ONLY, width = 120)

        val name = rule.onNodeWithText("Savings for the summer holidays").getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithTag(CONTROL_BADGE_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(badge.left > name.right && badge.right <= 120.dp, "the icon beside the wrapped name, inside the row")
    }

    @Test
    fun withNoRoomBesideTheNameTheBadgeGoesUnderIt() {
        show("Treasury", Control.CO_SIGNER, width = 70)

        val name = rule.onNodeWithText("Treasury").getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithTag(CONTROL_BADGE_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(badge.top >= name.bottom, "the badge under the name, the name not broken mid-word")
    }

    @Test
    fun rightToLeftTheBadgeComesBeforeTheNameFromTheRight() {
        show("Daily", Control.FULL_CONTROL, width = 320, direction = LayoutDirection.Rtl)

        val name = rule.onNodeWithText("Daily").getUnclippedBoundsInRoot()
        val badge = rule.onNodeWithText("Full control").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(badge.right < name.left, "mirrored: the name at the start, on the right, the badge to its left")
    }
}
