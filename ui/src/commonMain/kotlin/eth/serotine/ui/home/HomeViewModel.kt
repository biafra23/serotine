package eth.serotine.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.usecase.ObserveAccounts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Home screen's ViewModel: turns the domain's account stream into [HomeUiState], on the
 * [network] the composition root runs the app on. It holds no platform reference and exposes
 * state only through [uiState]; user intents, once the screen has any, become methods here
 * that call use cases.
 */
class HomeViewModel(observeAccounts: ObserveAccounts, network: Network) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = observeAccounts()
        .map { list ->
            when (list) {
                is AccountList.Loaded -> HomeUiState.Ready(
                    network.displayName,
                    list.accounts.map(AccountItem::from),
                    notice = if (list.damagedListSetAside) DAMAGED_SET_ASIDE else null,
                )
                is AccountList.Unreadable -> HomeUiState.Ready(
                    network.displayName,
                    emptyList(),
                    notice = when (list.reason) {
                        AccountList.Reason.NEWER_FORMAT -> NEWER_FORMAT
                        AccountList.Reason.READ_FAILED -> READ_FAILED
                    },
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = HomeUiState.Loading(network.displayName),
        )

    private companion object {
        const val DAMAGED_SET_ASIDE =
            "Your saved account list was damaged and has been set aside. Add your accounts again."
        const val NEWER_FORMAT =
            "Your account list was saved by a newer version of Serotine. Update the app to see it."
        const val READ_FAILED =
            "Your saved account list cannot be read right now. Nothing has been changed, and no " +
                "account can be added until it can be read."
    }
}
