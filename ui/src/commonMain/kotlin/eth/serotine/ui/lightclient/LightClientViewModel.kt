package eth.serotine.ui.lightclient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
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
    /** The network this screen works on: the only one until the network steps (docs/networks.md, part 3). */
    network: Network,
    /** Now, in unix seconds: what the strip dates the head block against. */
    clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** The strip as it is now; followed while a screen that draws it is shown. */
    val item: StateFlow<LightClientItem> = observeLightClient(network).asItems(clock)
        .stateIn(
            scope = viewModelScope,
            // Each subscription polls the engine's status (every second, on the desktop and
            // Android adapters), so it stops 5 s after the last screen with the strip has gone.
            // It keeps its last item, though: a screen opened later starts from the strip as it
            // was, never from "Starting" again, and the poll's first answer, read at once,
            // replaces it.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = LightClientItem.from(LightClientStatus.Starting, now = 0),
        )
}
