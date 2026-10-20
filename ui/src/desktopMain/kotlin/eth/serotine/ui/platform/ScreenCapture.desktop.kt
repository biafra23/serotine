package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * No desktop OS lets an app keep its window out of screenshots or recordings in a way it
 * can rely on, so the desktop protects nothing and says so (docs/keys-and-signing.md,
 * rule 8).
 */
@Composable
internal actual fun protectFromScreenCapture(blockCapture: Boolean): Boolean = false

/** The desktop sees no capture to warn about. */
@Composable
actual fun ScreenCaptureWarnings() = Unit
