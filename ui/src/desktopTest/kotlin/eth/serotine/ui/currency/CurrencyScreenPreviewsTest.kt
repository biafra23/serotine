package eth.serotine.ui.currency

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in CurrencyScreenPreviews.kt. */
class CurrencyScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(currencyScreenPreviews)
}
