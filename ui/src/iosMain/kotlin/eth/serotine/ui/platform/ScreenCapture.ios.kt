package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import eth.serotine.ui.components.ScreenCaptureEvent
import eth.serotine.ui.components.ScreenCaptureWarningDialog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol

/**
 * iOS has no API that blocks a screenshot, so this answers false and the screen keeps saying
 * that capture cannot be blocked. What rule 8 of docs/keys-and-signing.md asks for here instead,
 * while any caller is in composition: the window is covered while the app is not active, so the
 * app switcher's snapshot holds nothing; and a screenshot taken, or the screen recorded, mirrored
 * or shared (`UIScreen.isCaptured`), raises a warning, which [ScreenCaptureWarnings] shows.
 * Counted, because two protected screens overlap during a navigation transition. The app has
 * one window (its scene manifest allows no second one), so the app's active state is the
 * window's.
 */
@Composable
actual fun protectFromScreenCapture(): Boolean {
    val controller = LocalUIViewController.current
    DisposableEffect(controller) {
        acquire(controller)
        onDispose { release() }
    }
    return false
}

/**
 * The warning [protectFromScreenCapture] raised, until the user dismisses it. A screenshot's
 * outlives the screen it was taken of: the picture is still in Photos. A recording's goes when it
 * stops, or when no protected screen is left to record.
 */
@Composable
actual fun ScreenCaptureWarnings() {
    val event = warning.value ?: return
    ScreenCaptureWarningDialog(event, onDismiss = { warning.value = null })
}

// Touched only from composition, its effects and main-queue observers: the main thread.
private val warning = mutableStateOf<ScreenCaptureEvent?>(null)
private var holders = 0
private var observers: List<NSObjectProtocol> = emptyList()
private var cover: UIView? = null

private fun acquire(controller: UIViewController) {
    if (holders++ > 0) return
    val center = NSNotificationCenter.defaultCenter
    val main = NSOperationQueue.mainQueue
    observers = listOf(
        center.addObserverForName(UIApplicationWillResignActiveNotification, null, main) { _ -> cover(controller) },
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, main) { _ -> uncover() },
        center.addObserverForName(UIApplicationUserDidTakeScreenshotNotification, null, main) { _ ->
            warning.value = ScreenCaptureEvent.SCREENSHOT
        },
        center.addObserverForName(UIScreenCapturedDidChangeNotification, null, main) { _ -> followRecording() },
    )
    // Composed while the app is not active (behind a system prompt, say): no resign comes to say so.
    if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
        cover(controller)
    }
    // A recording that was running before the screen opened is warned about at once.
    followRecording()
}

private fun release() {
    if (--holders > 0) return
    observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
    observers = emptyList()
    uncover()
    if (warning.value == ScreenCaptureEvent.RECORDING) warning.value = null
}

/**
 * Raises the recording warning while the screen is captured, and takes it down once it no longer
 * is. `UIScreen.isCaptured` is deprecated from iOS 18 for the scene's capture state, which iOS
 * 15, the app's floor, does not have.
 */
private fun followRecording() {
    if (UIScreen.mainScreen.captured) {
        warning.value = ScreenCaptureEvent.RECORDING
    } else if (warning.value == ScreenCaptureEvent.RECORDING) {
        warning.value = null
    }
}

/** Lays a plain view over the whole window, above the Compose view, until [uncover]. */
@OptIn(ExperimentalForeignApi::class)
private fun cover(controller: UIViewController) {
    if (cover != null) return
    val window = controller.view.window ?: return
    val view = UIView(frame = window.bounds)
    view.backgroundColor = UIColor.systemBackgroundColor
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    window.addSubview(view)
    cover = view
}

private fun uncover() {
    cover?.removeFromSuperview()
    cover = null
}
