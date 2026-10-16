package eth.serotine.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The Home screen's ViewModel: turns the domain's account stream, each account's verified
 * balance and the light client's state into [HomeUiState], on the [network] the composition
 * root runs the app on. It holds no platform reference and exposes state only through
 * [uiState]; user intents, once the screen has any, become methods here that call use cases.
 */
class HomeViewModel(
    observeAccounts: ObserveAccounts,
    observeNativeBalance: ObserveNativeBalance,
    observeLightClient: ObserveLightClient,
    network: Network,
    /** Now, in unix seconds: what the strip dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows = observeAccounts()
        .flatMapLatest { accounts ->
            if (accounts.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    accounts.map { account ->
                        // A row from the start, "Checking…" until its first reading: an account
                        // just added, or a screen just back, never waits for every proof.
                        observeNativeBalance(account.address)
                            .map<BalanceReading, BalanceReading?> { it }
                            .onStart { emit(null) }
                            .map { AccountItem.from(account, it, network) }
                    },
                ) { items -> items.toList() }
            }
        }

    /** The strip, re-dated every second while it shows a head block's age, unchanged otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val strip = observeLightClient().flatMapLatest { status ->
        val dated = status is LightClientStatus.Synced && status.headTimestamp != null ||
            status is LightClientStatus.WaitingForProofPeers && status.headTimestamp != null
        val ticks = if (dated) everySecond else flowOf(Unit)
        ticks.map { LightClientItem.from(status, clock()) }
    }

    val uiState: StateFlow<HomeUiState> = combine(rows, strip) { items, lightClient ->
        HomeUiState.Ready(network.displayName, lightClient, items)
    }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone, and then forgets the
            // last state: a screen back after that must not show old balances as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = HomeUiState.Loading(
                network.displayName,
                LightClientItem.from(LightClientStatus.Starting, now = 0),
            ),
        )
}

private val everySecond = flow {
    while (true) {
        emit(Unit)
        delay(1.seconds)
    }
}

@OptIn(ExperimentalTime::class)
private fun epochSecondsNow(): Long = Clock.System.now().epochSeconds
