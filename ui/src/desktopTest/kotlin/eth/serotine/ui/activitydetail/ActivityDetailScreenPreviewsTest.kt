package eth.serotine.ui.activitydetail

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in ActivityDetailScreenPreviews.kt. */
class ActivityDetailScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("activityDetailScreen", activityDetailScreenPreviews)
}
