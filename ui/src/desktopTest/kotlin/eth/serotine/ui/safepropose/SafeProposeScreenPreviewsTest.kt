package eth.serotine.ui.safepropose

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SafeProposeScreenPreviews.kt. */
class SafeProposeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("safeProposeScreen", safeProposeScreenPreviews)
}
