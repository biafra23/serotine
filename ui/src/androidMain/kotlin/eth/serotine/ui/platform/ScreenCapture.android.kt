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
 * `FLAG_SECURE` on the activity's window while [blockCapture]: no screenshots, no screen
 * recording or casting, and a blank app-switcher snapshot. And, either way, the Compose view
 * kept out of the structure an autofill service reads, so a password manager can neither
 * fill nor offer to save what the screen's fields hold. Both are undone when the last caller
 * leaves composition.
 */
@Composable
internal actual fun protectFromScreenCapture(blockCapture: Boolean): Boolean {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(view, window, blockCapture) {
        acquire(view, window, blockCapture)
        onDispose { release(view, window, blockCapture) }
    }
    return window != null && blockCapture
}

/**
 * The protection a view is under, and how many composed callers hold it, and how many of
 * those block capture. Counted, because two protected screens overlap during a navigation
 * transition: the one leaving must not lift the protection from the one arriving. And
 * restored to exactly what was there before: a window that already carried FLAG_SECURE (a
 * host-wide setting, say) keeps it.
 */
private class Protection(val autofillBefore: Int, val secureBefore: Boolean) {
    var holders = 0
    var blockers = 0
}

// Touched only from composition effects, which run on the main thread.
private val protections = WeakHashMap<View, Protection>()

private fun acquire(view: View, window: Window?, blockCapture: Boolean) {
    val protection = protections.getOrPut(view) {
        val secureBefore = window != null &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        Protection(autofillBefore = view.importantForAutofill, secureBefore = secureBefore).also {
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
    protection.holders++
    if (blockCapture && protection.blockers++ == 0) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun release(view: View, window: Window?, blockCapture: Boolean) {
    val protection = protections[view] ?: return
    if (blockCapture && --protection.blockers == 0 && !protection.secureBefore) {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (--protection.holders > 0) return
    protections.remove(view)
    view.importantForAutofill = protection.autofillBefore
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
