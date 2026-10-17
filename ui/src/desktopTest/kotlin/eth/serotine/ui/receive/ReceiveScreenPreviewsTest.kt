package eth.serotine.ui.receive

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in ReceiveScreenPreviews.kt. */
class ReceiveScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(receiveScreenPreviews)
}
