package eth.serotine.ui.safeimport

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SafeImportScreenPreviews.kt. */
class SafeImportScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(safeImportScreenPreviews)
}
