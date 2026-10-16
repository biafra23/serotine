package eth.serotine.ui.removeaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AccountRemoval
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.RemoveAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The remove dialog's ViewModel: asks about the account under [address], then removes it. */
class RemoveAccountViewModel(
    private val address: Address,
    observeAccount: ObserveAccount,
    private val removeAccount: RemoveAccount,
) : ViewModel() {

    private val state = MutableStateFlow<RemoveAccountUiState>(RemoveAccountUiState.Loading)
    val uiState: StateFlow<RemoveAccountUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.value = when (val lookup = observeAccount(address).first()) {
                is AccountLookup.Found -> RemoveAccountUiState.Confirm(
                    label = lookup.account.label,
                    keyed = lookup.account.custody == AccountCustody.InKeyStore,
                )
                AccountLookup.NotInList -> RemoveAccountUiState.Gone
                AccountLookup.ListUnavailable ->
                    RemoveAccountUiState.Notice("Your account list cannot be read right now. Nothing was removed.")
            }
        }
    }

    /** Removes the account, once. */
    fun remove() {
        val confirm = state.value as? RemoveAccountUiState.Confirm ?: return
        if (confirm.removing) return
        state.value = confirm.copy(removing = true)
        viewModelScope.launch {
            state.value = when (removeAccount(address)) {
                AccountRemoval.Removed -> RemoveAccountUiState.Done
                AccountRemoval.NotFound -> RemoveAccountUiState.Gone
                AccountRemoval.RemovedKeyKept -> RemoveAccountUiState.Notice(
                    "${confirm.label} is removed, but its key could not be deleted from this device. " +
                        "Importing its recovery phrase again brings the account back.",
                )
                AccountRemoval.NotSaved ->
                    RemoveAccountUiState.Notice("${confirm.label} could not be removed. Nothing has changed.")
            }
        }
    }
}
