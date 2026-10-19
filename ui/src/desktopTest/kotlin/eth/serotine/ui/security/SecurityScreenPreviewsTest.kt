package eth.serotine.ui.security

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SecurityScreenPreviews.kt. */
class SecurityScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("securityScreen", securityScreenPreviews)
}
