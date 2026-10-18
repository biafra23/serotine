package eth.serotine.ui.createaccount

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in CreateAccountScreenPreviews.kt. */
class CreateAccountScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(createAccountScreenPreviews)
}
