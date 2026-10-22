package eth.serotine.ui.coworder

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in CowOrderScreenPreviews.kt. */
class CowOrderScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("cowOrderScreen", cowOrderScreenPreviews)
}
