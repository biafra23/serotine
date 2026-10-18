package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Whether the screen, as it stops, is stopping for a configuration change (a rotation, a fold or
 * an unfold, a resize, a theme or font change) and will be shown again at once, its ViewModel
 * kept: what the seed screens tell apart from the screen being left (owner ruling, 2026-10-18,
 * #39). Read at `ON_STOP`. Android's activity says it; the desktop and iOS have no such stop,
 * and answer false.
 */
@Composable
expect fun rememberChangingConfiguration(): () -> Boolean

/**
 * Calls [onStop] when the screen stops, unless it stops for a configuration change
 * ([isChangingConfigurations]): the seed screens' words stay in their ViewModel's memory across a
 * rotation, and go when the app goes to the background, the screen turns off or the screen is
 * popped (owner ruling, 2026-10-18, #39). Nothing is written to a saved state or a Bundle.
 */
@Composable
fun OnStopUnlessConfigurationChange(
    isChangingConfigurations: () -> Boolean = rememberChangingConfiguration(),
    onStop: () -> Unit,
) {
    val changing by rememberUpdatedState(isChangingConfigurations)
    val stop by rememberUpdatedState(onStop)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!changing()) stop() }
}
