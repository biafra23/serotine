package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import platform.UIKit.UIPasteboard

/** The general pasteboard. */
@Composable
actual fun rememberCopyText(): (String) -> Unit = { text -> UIPasteboard.generalPasteboard.string = text }

/** The share sheet comes with the iOS app; until then there is no Share button. */
@Composable
actual fun rememberShareText(): ((String) -> Unit)? = null
