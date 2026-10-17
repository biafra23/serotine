package eth.serotine.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.ui.platform.formatDateTime
import eth.serotine.ui.update.UpgradeBannerItem
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
 * balance, the light client's state and any network upgrade this version does not support
 * into [HomeUiState], on the [network] the composition root runs the app on. It holds no
 * platform reference and exposes state only through [uiState]; user intents, once the screen
 * has any, become methods here that call use cases.
 */
class HomeViewModel(
    observeAccounts: ObserveAccounts,
    observeNativeBalance: ObserveNativeBalance,
    observeLightClient: ObserveLightClient,
    observeUpgradeWarning: ObserveUpgradeWarning,
    network: Network,
    /** Now, in unix seconds: what the strip dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
    /** How the banner dates an upgrade (unix seconds). */
    formatTime: (Long) -> String = ::formatDateTime,
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

    /** The strip, re-dated every second while it shows a head block's age, unchanged otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val strip = observeLightClient().flatMapLatest { status ->
        val dated = status is LightClientStatus.Synced && status.headTimestamp != null ||
            status is LightClientStatus.WaitingForProofPeers && status.headTimestamp != null
        val ticks = if (dated) everySecond else flowOf(Unit)
        ticks.map { LightClientItem.from(status, clock()) }
    }

    /** The upgrade banner, none until the first warning: Home never waits for it. */
    private val upgrade = observeUpgradeWarning()
        .map { UpgradeBannerItem.from(it, network, formatTime) }
        .onStart { emit(null) }

    val uiState: StateFlow<HomeUiState> = combine(rows, strip, upgrade) { rows, lightClient, upgrade ->
        when (rows) {
            is Rows.Shown -> HomeUiState.Ready(network.displayName, lightClient, rows.items, rows.notice, upgrade)
            is Rows.Unavailable -> HomeUiState.ListUnavailable(network.displayName, lightClient, rows.message, upgrade)
        }
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

private val everySecond = flow {
    while (true) {
        emit(Unit)
        delay(1.seconds)
    }
}

@OptIn(ExperimentalTime::class)
private fun epochSecondsNow(): Long = Clock.System.now().epochSeconds
