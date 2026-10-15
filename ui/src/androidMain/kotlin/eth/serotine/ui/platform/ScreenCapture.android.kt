package eth.serotine.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * `FLAG_SECURE` on the activity's window: no screenshots, no screen recording or casting,
 * and a blank app-switcher snapshot. And the Compose view kept out of the structure an
 * autofill service reads, so a password manager can neither fill nor offer to save what
 * the screen's fields hold. Both are undone when the last caller leaves composition.
 */
@Composable
actual fun protectFromScreenCapture(): Boolean {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(view, window) {
        acquire(view, window)
        onDispose { release(view, window) }
    }
    return window != null
}

/**
 * The protection a view is under, and how many composed callers hold it. Counted, because
 * two protected screens overlap during a navigation transition: the one leaving must not
 * lift the protection from the one arriving.
 */
private class Protection(val autofillBefore: Int) {
    var holders = 0
}

// Touched only from composition effects, which run on the main thread.
private val protections = WeakHashMap<View, Protection>()

private fun acquire(view: View, window: Window?) {
    val protection = protections.getOrPut(view) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Protection(autofillBefore = view.importantForAutofill).also {
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
    protection.holders++
}

private fun release(view: View, window: Window?) {
    val protection = protections[view] ?: return
    if (--protection.holders > 0) return
    protections.remove(view)
    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    view.importantForAutofill = protection.autofillBefore
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
