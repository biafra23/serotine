package eth.serotine.ui.activity

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in ActivityScreenPreviews.kt. */
class ActivityScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(activityScreenPreviews)
}
