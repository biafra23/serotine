package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No stop here is a configuration change: a rotation keeps the view controller shown. */
@Composable
actual fun rememberChangingConfiguration(): () -> Boolean = remember { { false } }
