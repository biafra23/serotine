package eth.serotine.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.usecase.ObserveAccounts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Home screen's ViewModel: turns the domain's account stream into [HomeUiState]. It
 * holds no platform reference and exposes state only through [uiState]; user intents, once
 * the screen has any, become methods here that call use cases.
 */
class HomeViewModel(observeAccounts: ObserveAccounts) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = observeAccounts()
        .map { accounts -> HomeUiState.Ready(accounts.map(AccountItem::from)) }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = HomeUiState.Loading,
        )
}
