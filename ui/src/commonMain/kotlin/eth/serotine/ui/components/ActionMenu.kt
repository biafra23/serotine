package eth.serotine.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** One entry of an item's menu. [destructive] ones are drawn in the error colour. */
class MenuAction(
    val label: String,
    val icon: ImageVector?,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * [content] that opens [onClick] on a tap and its menu of [actions] on a long press, or on a
 * right click with a mouse (desktop). [menuLabel] is what accessibility services announce
 * for the long press. Every action must also be reachable from a visible control elsewhere
 * (docs/wallet-ux.md, *Long press is never the only way*).
 */
@Composable
fun WithActionMenu(
    actions: List<MenuAction>,
    menuLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val open by rememberUpdatedState { expanded = true }
    Box(
        modifier
            .combinedClickable(onClick = onClick, onLongClick = { open() }, onLongClickLabel = menuLabel)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            open()
                        }
                    }
                }
            },
    ) {
        content()
        ActionDropdown(actions, expanded, onDismiss = { expanded = false })
    }
}

/** The dropdown of [actions], open while [expanded]; choosing one closes it first. */
@Composable
fun ActionDropdown(actions: List<MenuAction>, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(action.label, color = color) },
                leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = color) } },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}
