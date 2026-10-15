package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * No desktop OS lets an app keep its window out of screenshots or recordings in a way it
 * can rely on, so the desktop protects nothing and says so (docs/keys-and-signing.md,
 * rule 8).
 */
@Composable
actual fun protectFromScreenCapture(): Boolean = false
