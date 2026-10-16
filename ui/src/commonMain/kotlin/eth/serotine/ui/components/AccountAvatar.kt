package eth.serotine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An account's picture: the [Blockie] of its [address], and for a Safe its [threshold] over
 * its owner count (`2/3`) on the corner (docs/wallet-ux.md, *Rules every screen follows*).
 */
@Composable
fun AccountAvatar(address: String, modifier: Modifier = Modifier, size: Dp = 40.dp, threshold: String? = null) {
    Box(modifier) {
        Blockie(address, size = size)
        if (threshold != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    threshold,
                    Modifier.padding(horizontal = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
