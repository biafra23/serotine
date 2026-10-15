package eth.serotine.ui.home

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind

/**
 * Everything the Home screen renders, as one immutable value: the screen is a pure
 * function of it, which is what makes the screen testable without a ViewModel and the
 * ViewModel testable without Compose.
 */
sealed interface HomeUiState {
    /** The account stream has not emitted yet. */
    data object Loading : HomeUiState

    /** The managed accounts, possibly none. */
    data class Ready(val accounts: List<AccountItem>) : HomeUiState
}

/** One account as the list shows it: display strings only, derived once from the domain model. */
data class AccountItem(
    val label: String,
    val address: String,
    val kind: AccountKind,
) {
    /** `0x1234…abcd`, for a list row; the full address stays in [address]. */
    val shortAddress: String
        get() = address.take(6) + "…" + address.takeLast(4)

    companion object {
        fun from(account: Account): AccountItem =
            AccountItem(label = account.label, address = account.address.hex, kind = account.kind)
    }
}
