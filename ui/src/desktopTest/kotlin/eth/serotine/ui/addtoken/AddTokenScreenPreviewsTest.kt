package eth.serotine.ui.addtoken

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in AddTokenScreenPreviews.kt. */
class AddTokenScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(addTokenScreenPreviews)
}
