package eth.serotine.ui.ratewidget

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in WidgetNetworkScreenPreviews.kt. */
class WidgetNetworkScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("widgetNetworkScreen", widgetNetworkScreenPreviews)
}
