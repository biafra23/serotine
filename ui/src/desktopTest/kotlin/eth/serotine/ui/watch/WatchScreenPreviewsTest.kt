package eth.serotine.ui.watch

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in WatchScreenPreviews.kt. */
class WatchScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(watchScreenPreviews)
}
