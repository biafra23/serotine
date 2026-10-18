package eth.serotine.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A token's picture: its bundled icon when [icon] names one ([TokenIcons]), otherwise a
 * monogram of its [symbol] — never one fetched from anywhere (docs/wallet-ux.md, *Tokens*).
 */
@Composable
fun TokenIcon(icon: String?, symbol: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val vector = icon?.let(TokenIcons::of)
    if (vector != null) {
        val painter: Painter = rememberVectorPainter(vector)
        Image(painter, contentDescription = null, modifier = modifier.size(size))
    } else {
        Surface(modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    monogram(symbol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The first two letters or digits of [symbol], upper-cased: "UNI" → "UN". */
internal fun monogram(symbol: String): String =
    symbol.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }
