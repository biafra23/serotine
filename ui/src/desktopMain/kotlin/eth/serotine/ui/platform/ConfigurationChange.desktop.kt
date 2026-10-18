package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No stop here is a configuration change: a window resized or a theme changed stays shown. */
@Composable
actual fun rememberChangingConfiguration(): () -> Boolean = remember { { false } }
