package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The owners held here who may sign a proposal ([owners]), one row each with its blockie, label and
 * short address, [owner] (EIP-55) chosen; [onChoose] with another's address while [enabled].
 */
@Composable
internal fun OwnerChoices(
    owners: List<AccountChoice>,
    owner: String,
    enabled: Boolean,
    onChoose: (address: String) -> Unit,
) {
    Column {
        owners.forEach { choice ->
            val selected = choice.address == owner
            ListItem(
                modifier = Modifier.selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onChoose(choice.address) },
                ),
                leadingContent = { AccountAvatar(choice.address, size = 32.dp) },
                headlineContent = { Text(choice.label) },
                supportingContent = { Text(shortAddress(choice.address), fontFamily = FontFamily.Monospace) },
                trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
            )
        }
    }
}
