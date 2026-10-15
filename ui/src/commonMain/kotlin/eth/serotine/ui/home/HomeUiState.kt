package eth.serotine.ui.home

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading

/**
 * Everything the Home screen renders, as one immutable value: the screen is a pure
 * function of it, which is what makes the screen testable without a ViewModel and the
 * ViewModel testable without Compose.
 */
sealed interface HomeUiState {
    /** The display name of the one network the app works on, for the top bar. */
    val network: String

    /** The embedded light client's state, for the status strip. */
    val lightClient: LightClientItem

    /** The account stream has not emitted yet. */
    data class Loading(override val network: String, override val lightClient: LightClientItem) : HomeUiState

    /** The managed accounts, possibly none; one row per address (the list keys rows on it). */
    data class Ready(
        override val network: String,
        override val lightClient: LightClientItem,
        val accounts: List<AccountItem>,
    ) : HomeUiState {
        init {
            require(accounts.distinctBy { it.address }.size == accounts.size) {
                "duplicate addresses in the account list: the repository contract is one entry per address"
            }
        }
    }
}

/**
 * One account as the list shows it: display strings only, derived once from the domain
 * model. [balance] is a verified amount ("0.5 ETH") when [balanceVerified], and otherwise
 * says why there is none ("Syncing…", "Not verified"): never a number from anywhere else.
 */
data class AccountItem(
    val label: String,
    val address: String,
    val kind: AccountKind,
    val balance: String,
    val balanceVerified: Boolean,
) {
    /** `0x1234…abcd`, for a list row; the full address stays in [address]. */
    val shortAddress: String
        get() = address.take(6) + "…" + address.takeLast(4)

    companion object {
        fun from(account: Account, balance: BalanceReading, network: Network): AccountItem = AccountItem(
            label = account.label,
            address = account.address.hex,
            kind = account.kind,
            balance = balanceText(balance, network),
            balanceVerified = balance is BalanceReading.Verified,
        )
    }
}
