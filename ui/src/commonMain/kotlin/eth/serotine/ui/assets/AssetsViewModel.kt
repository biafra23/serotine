package eth.serotine.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The Assets screen's ViewModel: turns the domain's account stream, each account's verified
 * balance and the light client's state into [AssetsUiState], on the [network] the
 * composition root runs the app on. It holds no platform reference and exposes state only
 * through [uiState]; what the user does with an account is navigation, which the screen's
 * route turns into the destination of the screen or dialog that does it.
 */
class AssetsViewModel(
    observeAccounts: ObserveAccounts,
    observeNativeBalance: ObserveNativeBalance,
    observeLightClient: ObserveLightClient,
    network: Network,
    /** Now, in unix seconds: what the strip dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** What the account list gives the screen: rows (with any notice), or why there are none. */
    private sealed interface Rows {
        data class Shown(val items: List<AccountItem>, val notice: String?) : Rows
        data class Unavailable(val message: String) : Rows
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows = observeAccounts()
        .flatMapLatest { list ->
            when (list) {
                is AccountList.Unreadable -> flowOf(
                    Rows.Unavailable(
                        when (list.reason) {
                            AccountList.Reason.NEWER_FORMAT -> NEWER_FORMAT
                            AccountList.Reason.READ_FAILED -> READ_FAILED
                        },
                    ),
                )
                is AccountList.Loaded -> {
                    val notice = if (list.damagedListSetAside) DAMAGED_SET_ASIDE else null
                    if (list.accounts.isEmpty()) {
                        flowOf(Rows.Shown(emptyList(), notice))
                    } else {
                        combine(
                            list.accounts.map { account ->
                                // A row from the start, "Checking…" until its first reading: an
                                // account just added, or a screen just back, never waits for
                                // every proof.
                                observeNativeBalance(account.address)
                                    .map<BalanceReading, BalanceReading?> { it }
                                    .onStart { emit(null) }
                                    .map { AccountItem.from(account, it, network) }
                            },
                        ) { items -> Rows.Shown(items.toList(), notice) }
                    }
                }
            }
        }

    private val strip = observeLightClient().asItems(clock)

    val uiState: StateFlow<AssetsUiState> = combine(rows, strip) { rows, lightClient ->
        when (rows) {
            is Rows.Shown -> AssetsUiState.Ready(network.displayName, lightClient, rows.items, rows.notice)
            is Rows.Unavailable -> AssetsUiState.ListUnavailable(network.displayName, lightClient, rows.message)
        }
    }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone, and then forgets the
            // last state: a screen back after that must not show old balances as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = AssetsUiState.Loading(
                network.displayName,
                LightClientItem.from(LightClientStatus.Starting, now = 0),
            ),
        )

    private companion object {
        const val DAMAGED_SET_ASIDE =
            "Your saved account list was damaged and has been set aside. Add your accounts again."
        const val NEWER_FORMAT =
            "Your account list was saved by a newer version of Serotine. Update the app to see it."
        const val READ_FAILED =
            "Your saved account list cannot be read right now. Nothing has been changed, and no " +
                "account can be added until it can be read. Serotine keeps trying."
    }
}
