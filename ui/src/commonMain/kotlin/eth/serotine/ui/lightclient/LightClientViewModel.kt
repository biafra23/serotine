package eth.serotine.ui.lightclient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The light client's status strip for the screens that draw it under their top bar
 * (docs/wallet-ux.md): one for the app, which `App` creates in the host's scope and hands to
 * those screens' routes, so each screen keeps its own UiState and ViewModel to what it does.
 * It holds no platform reference and exposes state only through [item].
 */
class LightClientViewModel(
    observeLightClient: ObserveLightClient,
    /** Now, in unix seconds: what the strip dates the head block against. */
    clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** The strip as it is now, whether or not a screen shows it. */
    val item: StateFlow<LightClientItem> = observeLightClient().asItems(clock)
        .stateIn(
            scope = viewModelScope,
            // Kept current from the start: a screen opened later draws the light client as it
            // is, never "Starting" again nor an earlier head while the flow restarts. It is
            // one map of a state already held, re-dated each second only while a head is.
            started = SharingStarted.Eagerly,
            initialValue = LightClientItem.from(LightClientStatus.Starting, now = 0),
        )
}
