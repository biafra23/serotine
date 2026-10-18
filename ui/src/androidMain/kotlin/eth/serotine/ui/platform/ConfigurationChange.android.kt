package eth.serotine.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The activity's `isChangingConfigurations`, read when asked: true while it stops to be made again. */
@Composable
actual fun rememberChangingConfiguration(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { { context.activity()?.isChangingConfigurations == true } }
}

/** The activity this context is, or wraps; null when there is none. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
