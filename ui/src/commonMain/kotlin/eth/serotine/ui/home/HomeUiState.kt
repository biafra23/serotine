package eth.serotine.ui.home

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind

/**
 * Everything the Home screen renders, as one immutable value: the screen is a pure
 * function of it, which is what makes the screen testable without a ViewModel and the
 * ViewModel testable without Compose.
 */
sealed interface HomeUiState {
    /** The display name of the one network the app works on, for the top bar. */
    val network: String

    /** The account stream has not emitted yet. */
    data class Loading(override val network: String) : HomeUiState

    /**
     * The managed accounts, possibly none; one row per address (the list keys rows on it).
     * [notice] tells the user, display-ready, when the stored list was damaged and set aside,
     * so these are only the accounts added since.
     */
    data class Ready(
        override val network: String,
        val accounts: List<AccountItem>,
        val notice: String? = null,
    ) : HomeUiState {
        init {
            require(accounts.distinctBy { it.address }.size == accounts.size) {
                "duplicate addresses in the account list: the repository contract is one entry per address"
            }
        }
    }

    /**
     * The stored account list exists but cannot be shown, and nothing can be added to it
     * until it can, for the reason [message] gives, display-ready. Not an empty list: the
     * screen must neither show "no accounts" nor invite an add that would be refused.
     */
    data class ListUnavailable(override val network: String, val message: String) : HomeUiState
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
