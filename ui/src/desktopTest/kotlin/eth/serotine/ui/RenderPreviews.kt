package eth.serotine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.assertTrue

/**
 * The directory [renderEach] writes each preview into as a PNG, from the `screenshots`
 * project property (`-Pscreenshots=<dir>`, ui/build.gradle.kts); null, and nothing is
 * written, otherwise. The PR screenshots CLAUDE.md asks for come from here.
 */
private val screenshotDir: File? = System.getProperty("serotine.screenshots")?.let(::File)

/**
 * Composes and draws each of [previews] headless, at a phone's size: a preview that throws
 * fails the test, so a broken one shows up in the build rather than in Android Studio. With
 * a [screenshotDir], each is also saved as `<name>-<n>.png`, n counting from 1 in the list's
 * order.
 */
fun renderEach(name: String, previews: List<@Composable () -> Unit>) {
    assertTrue(previews.isNotEmpty(), "no previews to render")
    screenshotDir?.mkdirs()
    previews.forEachIndexed { index, preview ->
        val scene = ImageComposeScene(width = 412, height = 892) { preview() }
        try {
            val image = scene.render()
            screenshotDir?.let { dir ->
                val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "$name ${index + 1}: no PNG" }
                File(dir, "$name-%02d.png".format(index + 1)).writeBytes(png.bytes)
            }
        } finally {
            scene.close()
        }
    }
}
