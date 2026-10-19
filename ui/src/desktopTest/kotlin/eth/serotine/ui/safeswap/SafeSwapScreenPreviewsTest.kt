package eth.serotine.ui.safeswap

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SafeSwapScreenPreviews.kt. */
class SafeSwapScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("safeSwapScreen", safeSwapScreenPreviews)
}
