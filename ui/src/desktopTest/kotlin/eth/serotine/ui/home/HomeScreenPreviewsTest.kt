package eth.serotine.ui.home

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in HomeScreenPreviews.kt. */
class HomeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(homeScreenPreviews)
}
