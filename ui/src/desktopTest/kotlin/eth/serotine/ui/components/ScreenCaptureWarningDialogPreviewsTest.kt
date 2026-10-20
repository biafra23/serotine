package eth.serotine.ui.components

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in ScreenCaptureWarningDialogPreviews.kt. */
class ScreenCaptureWarningDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("screenCaptureWarningDialog", screenCaptureWarningDialogPreviews)
}
