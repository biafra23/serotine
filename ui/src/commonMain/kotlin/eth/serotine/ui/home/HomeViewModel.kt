package eth.serotine.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Home screen's ViewModel: turns the domain's account stream, each account's verified
 * balance and the light client's state into [HomeUiState], on the [network] the composition
 * root runs the app on. It holds no platform reference and exposes state only through [uiState]; user
 * intents, once the screen has any, become methods here that call use cases.
 */
class HomeViewModel(
    observeAccounts: ObserveAccounts,
    observeNativeBalance: ObserveNativeBalance,
    observeLightClient: ObserveLightClient,
    network: Network,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows = observeAccounts()
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    accounts.map { account ->
                        observeNativeBalance(account.address).map { AccountItem.from(account, it, network) }
                    },
                ) { items -> items.toList() }
            }
        }

    val uiState: StateFlow<HomeUiState> = combine(rows, observeLightClient()) { items, lightClient ->
        HomeUiState.Ready(network.displayName, LightClientItem.from(lightClient), items)
    }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = HomeUiState.Loading(network.displayName, LightClientItem.from(LightClientStatus.Starting)),
        )
}
