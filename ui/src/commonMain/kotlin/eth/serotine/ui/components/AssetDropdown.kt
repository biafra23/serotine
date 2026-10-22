package eth.serotine.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Test tag of each asset in an open [AssetDropdown]'s menu, for UI tests. */
const val ASSET_OPTION_TAG = "asset-option"

/**
 * The chosen [selected] asset of [options], or [placeholder] while none is, in one line that
 * opens the menu of [options] on a tap: each with its icon and verified balance, the chosen one
 * ticked (the one whose contract is [selected]'s). Choosing one closes the menu and calls
 * [onSelect] with it. Held, greyed out and not opening, unless [enabled] and there are [options];
 * a menu open when the line is held closes. [tag] tags the line for UI tests.
 */
@Composable
internal fun AssetDropdown(
    options: List<AssetChoice>,
    selected: AssetChoice?,
    placeholder: String,
    enabled: Boolean,
    tag: String,
    onSelect: (AssetChoice) -> Unit,
) {
    val active = enabled && options.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    // Closed, not merely hidden: a line held while its menu was open must not reopen it when freed.
    LaunchedEffect(active) {
        if (!active) expanded = false
    }
    var width by remember { mutableStateOf(0) }
    Box {
        OutlinedCard(
            Modifier.fillMaxWidth().onSizeChanged { width = it.width },
            border = CardDefaults.outlinedCardBorder(active),
        ) {
            // The row is the control, inside the card so that its ripple keeps the card's shape.
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(tag)
                    .clickable(enabled = active, onClickLabel = placeholder, role = Role.DropdownList) {
                        expanded = true
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(if (active) 1f else 0.38f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    TokenIcon(selected.icon, selected.symbol)
                    AssetLines(selected, Modifier.weight(1f))
                } else {
                    Text(
                        placeholder,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(
            expanded = expanded && active,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(LocalDensity.current) { width.toDp() }),
        ) {
            options.forEach { asset ->
                DropdownMenuItem(
                    text = { AssetLines(asset) },
                    onClick = {
                        expanded = false
                        onSelect(asset)
                    },
                    modifier = Modifier.testTag(ASSET_OPTION_TAG),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    leadingIcon = { TokenIcon(asset.icon, asset.symbol) },
                    trailingIcon = if (selected != null && asset.contract == selected.contract) {
                        { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** An asset's symbol over its verified balance and, if it has one, its warning. */
@Composable
private fun AssetLines(asset: AssetChoice, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(asset.symbol, style = MaterialTheme.typography.bodyLarge)
        AssetBalanceLines(asset)
    }
}
