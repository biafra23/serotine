package eth.serotine.ui.createsafe

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in CreateSafeScreenPreviews.kt. */
class CreateSafeScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("createSafeScreen", createSafeScreenPreviews)
}
