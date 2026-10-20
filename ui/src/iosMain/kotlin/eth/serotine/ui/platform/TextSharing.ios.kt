package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/** The general pasteboard. */
@Composable
actual fun rememberCopyText(): (String) -> Unit = { text -> UIPasteboard.generalPasteboard.string = text }

/**
 * The system share sheet (`UIActivityViewController`), presented over whatever the app shows; a
 * second tap while a sheet is up shows no second one. On an iPad the sheet is a popover,
 * anchored to the middle of the screen.
 */
@Composable
actual fun rememberShareText(): ((String) -> Unit)? {
    val controller = LocalUIViewController.current
    return remember(controller) { { text -> share(controller, text) } }
}

@OptIn(ExperimentalForeignApi::class)
private fun share(controller: UIViewController, text: String) {
    var top: UIViewController = controller
    while (true) top = top.presentedViewController?.takeUnless { it.beingDismissed } ?: break
    if (top is UIActivityViewController) return
    val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    sheet.popoverPresentationController?.let { popover ->
        popover.sourceView = top.view
        popover.sourceRect = top.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
    }
    top.presentViewController(sheet, animated = true, completion = null)
}
