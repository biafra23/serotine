package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * iOS's idle timer is the application's, so holding it (`UIApplication.idleTimerDisabled`) is
 * the iOS host's to wire with the rest of its screen handling, and it does not exist yet.
 */
@Composable
actual fun KeepScreenOn() = Unit
