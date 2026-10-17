package eth.serotine.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The update screen's ViewModel, which also decides whether the app shows anything else:
 * [UpdateRequiredUiState.Required] exactly while the domain says an upgrade on [network] has
 * left this version unable to follow it ([UpgradeWarning.UpdateRequired]: activated, and the
 * light client agrees it is not verifying). The app's host scopes it, not a destination,
 * since the destinations are what it replaces.
 */
class UpdateRequiredViewModel(
    observeUpgradeWarning: ObserveUpgradeWarning,
    network: Network,
    /** How the screen dates the upgrade (unix seconds). */
    formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    val uiState: StateFlow<UpdateRequiredUiState> = observeUpgradeWarning()
        .map { warning ->
            when (warning) {
                is UpgradeWarning.UpdateRequired -> UpdateRequiredUiState.Required(
                    text = updateRequiredText(warning.upgrade, network, formatTime),
                    details = detailsOf(warning.upgrade),
                    version = "This is Serotine v$APP_VERSION.",
                )
                UpgradeWarning.None, is UpgradeWarning.Ahead, is UpgradeWarning.Activated ->
                    UpdateRequiredUiState.NotRequired
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = UpdateRequiredUiState.NotRequired,
        )
}
