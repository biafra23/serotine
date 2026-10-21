package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the window's content out of screenshots, screen recordings and the app
 * switcher's snapshot for as long as the caller is in composition, where the platform
 * can: the screen protections docs/keys-and-signing.md (rule 8) puts on every screen that
 * shows a seed or takes the password. In a debug build whose [ScreenCaptureDebugSwitch]
 * allows capture, the window is left capturable; the rest of the protection stays.
 *
 * Returns whether this platform protects the window. A screen that gets false tells the
 * user so, rather than implying a protection that is not there.
 */
@Composable
fun protectFromScreenCapture(): Boolean {
    val switch = LocalScreenCaptureDebugSwitch.current
    val allowed = if (switch == null) false else switch.allowed.collectAsState().value
    return protectFromScreenCapture(blockCapture = !allowed)
}

/**
 * The platform's half of [protectFromScreenCapture]: the window kept out of capture when
 * [blockCapture], and what else the platform keeps from the screen's fields either way.
 * Returns whether capture is blocked.
 */
@Composable
internal expect fun protectFromScreenCapture(blockCapture: Boolean): Boolean

/**
 * The debug build's way to make a screen recording of the protected screens (owner's
 * request, 2026-10-21; docs/keys-and-signing.md, rule 8): while [allowed], the screens
 * that show a seed or take the password leave the window capturable, and say so. Settings
 * offers it. Only a debug host builds one; a release build has none, so nothing can lift
 * the protection there.
 *
 * Held in memory only: every start of the app begins with capture blocked.
 */
class ScreenCaptureDebugSwitch {
    private val state = MutableStateFlow(false)

    /** Whether the protected screens leave the window capturable. */
    val allowed: StateFlow<Boolean> = state.asStateFlow()

    /** Allows capture of the protected screens, or blocks it again. */
    fun allow(allowed: Boolean) {
        state.value = allowed
    }
}

/** The host's [ScreenCaptureDebugSwitch], provided by `App`; null outside a debug build. */
internal val LocalScreenCaptureDebugSwitch = staticCompositionLocalOf<ScreenCaptureDebugSwitch?> { null }

/**
 * Where a platform that cannot block a capture but sees one happen (iOS) warns about it: once,
 * at the root of [eth.serotine.ui.App], so a warning outlives the protected screen it was raised
 * on and two screens overlapping in a navigation transition show one. Draws nothing elsewhere.
 */
@Composable
expect fun ScreenCaptureWarnings()
