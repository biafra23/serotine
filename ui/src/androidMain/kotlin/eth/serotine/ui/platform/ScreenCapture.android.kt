package eth.serotine.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * `FLAG_SECURE` on the activity's window: no screenshots, no screen recording or casting,
 * and a blank app-switcher snapshot. And the Compose view kept out of the structure an
 * autofill service reads, so a password manager can neither fill nor offer to save what
 * the screen's fields hold. Both are undone when the caller leaves composition.
 */
@Composable
actual fun protectFromScreenCapture(): Boolean {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(view, window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val autofill = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view.importantForAutofill = autofill
        }
    }
    return window != null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
