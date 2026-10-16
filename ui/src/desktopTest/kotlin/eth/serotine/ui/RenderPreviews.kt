package eth.serotine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import kotlin.test.assertTrue

/**
 * Composes and draws each of [previews] headless, at a phone's size: a preview that throws
 * fails the test, so a broken one shows up in the build rather than in Android Studio.
 */
fun renderEach(previews: List<@Composable () -> Unit>) {
    assertTrue(previews.isNotEmpty(), "no previews to render")
    for (preview in previews) {
        val scene = ImageComposeScene(width = 412, height = 892) { preview() }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }
}
