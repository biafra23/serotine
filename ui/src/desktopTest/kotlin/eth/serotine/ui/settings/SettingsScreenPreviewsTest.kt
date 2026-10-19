package eth.serotine.ui.settings

import eth.serotine.ui.renderEach
import kotlin.test.Test

/** Renders every preview in SettingsScreenPreviews.kt. */
class SettingsScreenPreviewsTest {

    @Test
    fun everyPreviewRenders() = renderEach("settingsScreen", settingsScreenPreviews)
}
