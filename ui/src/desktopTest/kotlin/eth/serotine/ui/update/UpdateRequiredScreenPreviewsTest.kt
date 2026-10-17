package eth.serotine.ui.update

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in UpdateRequiredScreenPreviews.kt. */
class UpdateRequiredScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(updateRequiredScreenPreviews)
}
