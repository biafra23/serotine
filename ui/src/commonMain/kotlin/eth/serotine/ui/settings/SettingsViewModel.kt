package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Settings screen's ViewModel: the [network], the light client's state, the app's version. */
class SettingsViewModel(
    observeLightClient: ObserveLightClient,
    network: Network,
    /** Now, in unix seconds: what the light client's line dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = observeLightClient()
        .asItems(clock)
        .map { SettingsUiState(network.displayName, it, APP_VERSION) }
        .stateIn(
            viewModelScope,
            // A tab back after the timeout must not show an old head as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SettingsUiState(network.displayName, LightClientItem.from(LightClientStatus.Starting, 0), APP_VERSION),
        )
}
