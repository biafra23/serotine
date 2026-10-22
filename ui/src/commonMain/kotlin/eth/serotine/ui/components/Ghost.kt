package eth.serotine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Test tag of each ghost row, for UI tests. */
const val GHOST_ROW_TAG = "ghost-row"

/**
 * A grey bar standing in for one line of text not read yet, [width] wide and exactly as tall as
 * a line in [style] (the slot's own style by default, as a [Text] there would have), so that the
 * text that replaces it moves nothing around it.
 */
@Composable
fun GhostLine(width: Dp, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val color = MaterialTheme.colorScheme.surfaceVariant
    // An empty text measures one line of its style: the bar is drawn over its middle.
    Text(
        "",
        modifier.width(width).clearAndSetSemantics {}.drawBehind {
            val inset = size.height * 0.2f
            drawRoundRect(
                color,
                topLeft = Offset(0f, inset),
                size = Size(size.width, size.height - 2 * inset),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        },
        style = style,
    )
}

/** A grey circle standing in for an icon or a blockie of [size] not drawn yet. */
@Composable
fun GhostCircle(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
}

/**
 * [count] ghost rows, [spacing] apart as the rows they stand in for are, each drawn by [row] in
 * the shape of such a row, and said to accessibility services as [description] ("Reading the
 * Safe's storage"): a list whose rows are on their way takes its room before they come, rather
 * than pushing what is below it down when they do.
 */
@Composable
fun GhostRows(
    count: Int,
    description: String,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    row: @Composable () -> Unit,
) {
    // One node for accessibility services; each row keeps its test tag in the unmerged tree.
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(count) {
            Box(Modifier.testTag(GHOST_ROW_TAG)) { row() }
        }
    }
}

/** How many ghost rows a list whose number of rows is not known yet shows while it is read. */
const val GHOST_ROWS_UNKNOWN = 3
