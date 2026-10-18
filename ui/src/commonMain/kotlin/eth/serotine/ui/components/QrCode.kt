package eth.serotine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * [text] as a QR code, dark modules on white with the four-module quiet zone scanners need:
 * always light, whatever the theme, because a dark-mode inversion is what many scanners
 * cannot read. [description] is what a screen reader says for it.
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrMatrix.encode(text.encodeToByteArray()) }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        val modulesPerSide = matrix.size + 2 * QUIET_ZONE
        val module = size.width / modulesPerSide
        drawRect(Color.White)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QUIET_ZONE) * module, (y + QUIET_ZONE) * module),
                        // A hair wider, so neighbouring modules leave no anti-aliased seam.
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}

/** Light modules around the code on every side, as the standard asks. */
private const val QUIET_ZONE = 4
