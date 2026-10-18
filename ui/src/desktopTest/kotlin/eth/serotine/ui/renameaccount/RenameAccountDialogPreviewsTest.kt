package eth.serotine.ui.renameaccount

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in RenameAccountDialogPreviews.kt. */
class RenameAccountDialogPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach(renameAccountDialogPreviews)
}
