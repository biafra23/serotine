package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * Keeps the window's content out of screenshots, screen recordings and the app
 * switcher's snapshot for as long as the caller is in composition, where the platform
 * can: the screen protections docs/keys-and-signing.md (rule 8) puts on every screen that
 * shows a seed or takes the password.
 *
 * Returns whether this platform protects the window. A screen that gets false tells the
 * user so, rather than implying a protection that is not there.
 */
@Composable
expect fun protectFromScreenCapture(): Boolean

/**
 * Where a platform that cannot block a capture but sees one happen (iOS) warns about it: once,
 * at the root of [eth.serotine.ui.App], so a warning outlives the protected screen it was raised
 * on and two screens overlapping in a navigation transition show one. Draws nothing elsewhere.
 */
@Composable
expect fun ScreenCaptureWarnings()
