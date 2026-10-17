package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * An EIP-55 [address] with its blockie and shortened, after what it is ([what], if said): the
 * line a Safe's modules and guards are drawn with, on the Watch card and the Owners view. The
 * address keeps its one line; a long [what] wraps in the width left beside it.
 */
@Composable
fun AddressLine(address: String, what: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Weighted, so the row measures the blockie and the address first and the label gets the rest.
        what?.let { Text(it, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall) }
        Blockie(address, size = 20.dp)
        Text(
            shortAddress(address),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
        )
    }
}
