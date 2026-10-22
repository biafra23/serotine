package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveDebugScreen
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.SetDebugScreen
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings screen's ViewModel: the display currency, the known tokens, the [network], the
 * light client's state, the Debug tab's switch and the version; and, in a debug build, the
 * switch that allows screen recordings of the protected screens.
 */
class SettingsViewModel(
    observeLightClient: ObserveLightClient,
    observeKnownTokens: ObserveKnownTokens,
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    network: Network,
    /** The debug build's screen-capture switch; null in a release build, which offers none. */
    private val screenCapture: ScreenCaptureDebugSwitch?,
    observeDebugScreen: ObserveDebugScreen,
    private val setDebugScreen: SetDebugScreen,
    /** Now, in unix seconds: what the light client's line dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** Whether the last switch of the Debug tab could not be saved. */
    private val debugScreenNotSaved = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            observeLightClient().asItems(clock),
            observeKnownTokens(),
            observeDisplayCurrencies(),
            screenCapture?.allowed ?: flowOf(null),
            combine(observeDebugScreen(), debugScreenNotSaved, ::Pair),
        ) { item, known, currencies, captureAllowed, (debugScreen, notSaved) ->
            val builtIn = known.tokens.count { it.origin == TokenOrigin.BUILT_IN }
            val added = if (known.settingsUnavailable) {
                "added ones cannot be read"
            } else {
                "${known.tokens.size - builtIn} added"
            }
            val tokens = "$builtIn built in · $added"
            val currency = currencies.chosen.code + when {
                currencies.unreadable -> " (your choice cannot be read)"
                currencies.setAside -> " (your saved choice was damaged and set aside)"
                else -> ""
            }
            SettingsUiState(
                network.displayName,
                item,
                APP_VERSION,
                tokens,
                currency,
                captureAllowed,
                debugScreen,
                notSaved,
            )
        }
        .stateIn(
            viewModelScope,
            // A tab back after the timeout must not show an old head as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SettingsUiState(
                network.displayName,
                LightClientItem.from(LightClientStatus.Starting, 0),
                APP_VERSION,
                screenCaptureAllowed = screenCapture?.allowed?.value,
            ),
        )

    /** Allows screen recordings of the protected screens, or blocks them again; a release build has no switch. */
    fun allowScreenCapture(allowed: Boolean) {
        screenCapture?.allow(allowed)
    }

    /**
     * Shows the Debug tab, or hides it, which empties its log. A switch that cannot be saved
     * leaves the row as it was, saying so.
     */
    fun showDebugScreen(shown: Boolean) {
        viewModelScope.launch { debugScreenNotSaved.value = !setDebugScreen(shown) }
    }
}
