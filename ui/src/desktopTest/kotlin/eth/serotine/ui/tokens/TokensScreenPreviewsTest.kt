package eth.serotine.ui.tokens

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in TokensScreenPreviews.kt. */
class TokensScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("tokensScreen", tokensScreenPreviews)
}
