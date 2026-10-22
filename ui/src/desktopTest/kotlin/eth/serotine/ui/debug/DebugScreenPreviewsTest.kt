package eth.serotine.ui.debug

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in DebugScreenPreviews.kt. */
class DebugScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("debugScreen", debugScreenPreviews)
}
