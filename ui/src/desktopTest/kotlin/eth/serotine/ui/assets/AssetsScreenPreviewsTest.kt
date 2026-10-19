package eth.serotine.ui.assets

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in AssetsScreenPreviews.kt. */
class AssetsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("assetsScreen", assetsScreenPreviews)
}
