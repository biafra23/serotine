package eth.serotine.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.ObserveDebugScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the bottom bar shows the Debug tab, as the user set it in Settings: one for the app,
 * which `App` creates in the host's scope, as the tabs are the app's and no one screen's.
 */
class DebugTabViewModel(observeDebugScreen: ObserveDebugScreen) : ViewModel() {
    /** Whether the tab shows; null until the setting has been read, so nothing acts on a guess. */
    val shown: StateFlow<Boolean?> = observeDebugScreen().stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
