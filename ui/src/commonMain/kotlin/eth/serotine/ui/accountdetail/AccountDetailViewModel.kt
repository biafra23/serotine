package eth.serotine.ui.accountdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The account detail screen's ViewModel: the account under [address] and its verified native
 * balance on [network], as [AccountDetailUiState]. What the user does from the screen is
 * navigation (Receive, Rename, Remove) or the clipboard, which the route handles.
 */
class AccountDetailViewModel(
    address: Address,
    observeAccount: ObserveAccount,
    observeNativeBalance: ObserveNativeBalance,
    network: Network,
) : ViewModel() {

    private val balance = observeNativeBalance(address)
        .map<BalanceReading, BalanceReading?> { it }
        .onStart { emit(null) }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(address)

    val uiState: StateFlow<AccountDetailUiState> = combine(observeAccount(address), balance) { lookup, reading ->
        when (lookup) {
            is AccountLookup.Found -> AccountDetailUiState.Ready(
                label = lookup.account.label,
                address = display,
                kind = lookup.account.kind,
                control = Control.of(lookup.account.kind, lookup.account.custody),
                balance = reading?.let { balanceText(it, network) } ?: "Checking…",
                balanceVerified = reading is BalanceReading.Verified,
                balanceNote = (reading as? BalanceReading.Verified)?.let {
                    "Verified at block ${grouped(it.blockNumber)}"
                },
            )
            AccountLookup.NotInList -> AccountDetailUiState.Gone
            AccountLookup.ListUnavailable -> AccountDetailUiState.Unavailable(LIST_UNAVAILABLE)
        }
    }.stateIn(
        scope = viewModelScope,
        // As on Assets: a screen back after the timeout must not show an old balance as current.
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        initialValue = AccountDetailUiState.Loading,
    )

    private companion object {
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
    }
}
