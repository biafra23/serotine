package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/**
 * The application's idle timer held off (`UIApplication.idleTimerDisabled`) while any caller is
 * in composition, and given back as it was when the last one leaves: counted, because two
 * callers overlap during a navigation transition.
 */
@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        val app = UIApplication.sharedApplication
        if (screenOnHolders++ == 0) {
            idleTimerBefore = app.idleTimerDisabled
            app.idleTimerDisabled = true
        }
        onDispose {
            // Given back only if still as this left it: a change made meanwhile stands.
            if (--screenOnHolders == 0 && app.idleTimerDisabled) app.idleTimerDisabled = idleTimerBefore
        }
    }
}

// Touched only from composition effects, which run on the main thread.
private var screenOnHolders = 0
private var idleTimerBefore = false
