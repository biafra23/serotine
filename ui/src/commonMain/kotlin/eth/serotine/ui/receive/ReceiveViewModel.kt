package eth.serotine.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Receive screen's ViewModel: the account under [address], to receive to on [network]. */
class ReceiveViewModel(address: Address, observeAccount: ObserveAccount, network: Network) : ViewModel() {

    val uiState: StateFlow<ReceiveUiState> = observeAccount(address)
        .map { lookup ->
            when (lookup) {
                is AccountLookup.Found -> ReceiveUiState.Ready(
                    label = lookup.account.label,
                    address = Eip55.format(lookup.account.address),
                    notice = when (lookup.account.kind) {
                        // A Safe is a contract: at the same address on another network there may
                        // be nothing, or someone else's.
                        AccountKind.SAFE -> "This Safe exists only on ${network.displayName}"
                        AccountKind.EOA -> "Only send ${network.displayName} assets to this address"
                    },
                )
                AccountLookup.NotInList -> ReceiveUiState.Gone
                AccountLookup.ListUnavailable ->
                    ReceiveUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying.")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceiveUiState.Loading)
}
