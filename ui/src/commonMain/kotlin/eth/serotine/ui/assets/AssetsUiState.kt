package eth.serotine.ui.assets

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.valuationText

/**
 * Everything the Assets screen renders, as one immutable value: the screen is a pure
 * function of it, which is what makes the screen testable without a ViewModel and the
 * ViewModel testable without Compose.
 */
sealed interface AssetsUiState {
    /** The display name of the one network the app works on, for the top bar. */
    val network: String

    /** The embedded light client's state, for the status strip. */
    val lightClient: LightClientItem

    /** The account stream has not emitted yet. */
    data class Loading(override val network: String, override val lightClient: LightClientItem) : AssetsUiState

    /**
     * The managed accounts, possibly none; one row per address (the list keys rows on it).
     * [notice] tells the user, display-ready, when the stored list was damaged and set aside,
     * so these are only the accounts added since. [total] is the portfolio's in the display
     * currency ("$3,250.00", or "Checking…"; null with no accounts) and [totalNote] what it
     * leaves out ("Testnet prices · 1 asset without a price").
     */
    data class Ready(
        override val network: String,
        override val lightClient: LightClientItem,
        val accounts: List<AccountItem>,
        val notice: String? = null,
        val total: String? = null,
        val totalNote: String? = null,
    ) : AssetsUiState {
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
    data class ListUnavailable(
        override val network: String,
        override val lightClient: LightClientItem,
        val message: String,
    ) : AssetsUiState
}

/**
 * One account as the list shows it: display strings only, derived once from the domain
 * model. [address] is EIP-55 checksummed. [balance] is a verified amount ("0.5 ETH") when
 * [balanceVerified], and otherwise says why there is none ("Checking…", "Syncing…", "Not
 * verified"): never a number from anywhere else. [value] is that balance in the display
 * currency, or why it has none ("No price"; null while the balance is not verified).
 */
data class AccountItem(
    val label: String,
    val address: String,
    val kind: AccountKind,
    val control: Control,
    val balance: String,
    val balanceVerified: Boolean,
    val value: String? = null,
) {
    /** `0x1234…abcd`, for a list row; the full address stays in [address]. */
    val shortAddress: String
        get() = shortAddress(address)

    companion object {
        /**
         * [account] with its [balance], or with none read yet ([balance] null), and that
         * balance's [value] in [currency]; [address] is its EIP-55 form, which a caller that
         * re-renders the row per balance computes once.
         */
        fun from(
            account: Account,
            balance: BalanceReading?,
            network: Network,
            address: String = Eip55.format(account.address),
            value: Valuation? = null,
            currency: DisplayCurrency = DisplayCurrency.USD,
        ): AccountItem = AccountItem(
            label = account.label,
            address = address,
            kind = account.kind,
            control = Control.of(account.kind, account.custody),
            balance = balance?.let { balanceText(it, network) } ?: "Checking…",
            balanceVerified = balance is BalanceReading.Verified,
            value = valuationText(value, currency),
        )
    }
}

/** What the user asks of an account row, by its [AccountItem.address]. */
sealed interface AccountAction {
    val address: String

    /** Open its detail screen (a tap). */
    data class Open(override val address: String) : AccountAction

    /** Show its address to receive to. */
    data class Receive(override val address: String) : AccountAction

    /** Put its address on the clipboard. */
    data class Copy(override val address: String) : AccountAction

    /** Give it another name. */
    data class Rename(override val address: String) : AccountAction

    /** Take it out of the wallet. */
    data class Remove(override val address: String) : AccountAction
}
