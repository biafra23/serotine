package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Settings screen's ViewModel: the display currency, the known tokens, the [network], the
 * light client's state and the version.
 */
class SettingsViewModel(
    observeLightClient: ObserveLightClient,
    observeKnownTokens: ObserveKnownTokens,
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    network: Network,
    /** Now, in unix seconds: what the light client's line dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            observeLightClient().asItems(clock),
            observeKnownTokens(),
            observeDisplayCurrencies(),
        ) { item, known, currencies ->
            val builtIn = known.tokens.count { it.origin == TokenOrigin.BUILT_IN }
            val added = if (known.settingsUnavailable) {
                "added ones cannot be read"
            } else {
                "${known.tokens.size - builtIn} added"
            }
            val tokens = "$builtIn built in · $added"
            val currency = currencies.chosen.code + if (currencies.unreadable) " (your choice cannot be read)" else ""
            SettingsUiState(network.displayName, item, APP_VERSION, tokens, currency)
        }
        .stateIn(
            viewModelScope,
            // A tab back after the timeout must not show an old head as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SettingsUiState(network.displayName, LightClientItem.from(LightClientStatus.Starting, 0), APP_VERSION),
        )
}
