package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * One labelled entry of a [SpeedDialFab]; [unavailable] when something it needs is missing: then
 * it is greyed out and [onClick] says why ([Unavailable]), never hidden.
 */
class SpeedDialAction(
    val label: String,
    val icon: ImageVector,
    val unavailable: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A floating action button that fans out into its labelled [actions] (docs/wallet-ux.md,
 * screen 2) and closes again on a second tap or once one is chosen. [description] is what a
 * screen reader says for the closed button; [expandedAtStart] opens it from the start (a preview).
 */
@Composable
fun SpeedDialFab(
    actions: List<SpeedDialAction>,
    description: String,
    modifier: Modifier = Modifier,
    expandedAtStart: Boolean = false,
) {
    var expanded by remember { mutableStateOf(expandedAtStart) }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expanded) {
            actions.forEach { action ->
                val choose = {
                    expanded = false
                    action.onClick()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val content = if (action.unavailable) Unavailable.contentColor() else LocalContentColor.current
                    Surface(
                        onClick = choose,
                        modifier = Modifier.unavailable(action.unavailable),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 2.dp,
                    ) {
                        Text(action.label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = content)
                    }
                    // The label says it; the icon would only repeat it to a screen reader.
                    SmallFloatingActionButton(
                        onClick = choose,
                        containerColor = if (action.unavailable) {
                            Unavailable.containerColor()
                        } else {
                            FloatingActionButtonDefaults.containerColor
                        },
                    ) { Icon(action.icon, contentDescription = null, tint = content) }
                }
            }
        }
        FloatingActionButton(onClick = { expanded = !expanded }) {
            if (expanded) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            } else {
                Icon(Icons.Filled.Add, contentDescription = description)
            }
        }
    }
}
