package eth.serotine.ui.accountdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.TokenHolding
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.unitsAsNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The account detail screen's ViewModel: the account under [address], its verified native
 * balance on [network] and what it holds of each known token, as [AccountDetailUiState]. Its
 * one intent is the rows' order ([sortBy]); what else the user does from the screen is
 * navigation (Receive, Rename, Remove) or the clipboard, which the route handles.
 */
class AccountDetailViewModel(
    address: Address,
    observeAccount: ObserveAccount,
    observeNativeBalance: ObserveNativeBalance,
    observeTokenHoldings: ObserveTokenHoldings,
    private val network: Network,
) : ViewModel() {

    private val balance = observeNativeBalance(address)
        .map<BalanceReading, BalanceReading?> { it }
        .onStart { emit(null) }

    private val sort = MutableStateFlow(AssetSort.NAME)

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(address)

    private val holdings = observeTokenHoldings(address)

    val uiState: StateFlow<AccountDetailUiState> =
        combine(observeAccount(address), balance, holdings, sort) { lookup, reading, held, order ->
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
                    assets = assets(reading, held, order),
                    sort = order,
                    tokensNote = tokensNote(held),
                    tokensBlockNote = tokensBlockNote(held),
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

    /** Orders the asset rows by [order]. */
    fun sortBy(order: AssetSort) {
        sort.value = order
    }

    /** A row and the number it sorts by under [AssetSort.AMOUNT] (null: not verified, last). */
    private class Sortable(val row: AssetRow, val amount: Double?)

    /** The native row, and a row per token held: a token whose verified balance is zero is not held. */
    private fun assets(native: BalanceReading?, holdings: List<TokenHolding>, order: AssetSort): List<AssetRow> {
        val nativeRow = Sortable(
            AssetRow(
                symbol = network.nativeSymbol,
                name = network.nativeName,
                amount = native?.let { balanceText(it, network) } ?: "Checking…",
                icon = network.nativeIcon,
                verified = native is BalanceReading.Verified,
            ),
            (native as? BalanceReading.Verified)?.let { unitsAsNumber(it.wei.decimal, NATIVE_DECIMALS) },
        )
        val tokenRows = holdings.mapNotNull { holding ->
            val verified = holding.reading as? TokenBalanceReading.Verified ?: return@mapNotNull null
            if (verified.units.isZero) return@mapNotNull null
            val token = holding.token
            Sortable(
                AssetRow(
                    token.symbol,
                    token.name,
                    formatUnits(verified.units.decimal, token.decimals, token.symbol),
                    token.icon,
                    verified = true,
                ),
                unitsAsNumber(verified.units.decimal, token.decimals),
            )
        }
        val rows = listOf(nativeRow) + tokenRows
        val sorted = when (order) {
            AssetSort.NAME -> rows.sortedBy { it.row.symbol.lowercase() }
            AssetSort.AMOUNT ->
                rows.sortedWith(compareBy<Sortable> { it.amount == null }.thenByDescending { it.amount })
        }
        return sorted.map { it.row }
    }

    /** What is not known (yet) about the tokens no row shows: still being checked, or why not. */
    private fun tokensNote(holdings: List<TokenHolding>): String? {
        val checking = holdings.count { it.reading == null || it.reading == TokenBalanceReading.Syncing }
        val unverified = holdings.count { it.reading is TokenBalanceReading.Unavailable }
        val notTokens = holdings.count { it.reading == TokenBalanceReading.NotAToken }
        val parts = listOfNotNull(
            checking.takeIf { it > 0 }?.let { "checking ${tokens(it)}" },
            unverified.takeIf { it > 0 }?.let { "${tokens(it)} not verified" },
            notTokens.takeIf { it > 0 }?.let { "${tokens(it)} did not answer as a token" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
    }

    private fun tokens(count: Int) = if (count == 1) "1 token" else "$count tokens"

    /**
     * Where the token amounts shown were verified: the block of the newest reading, which may
     * be another than the native balance's; null when no token row is shown.
     */
    private fun tokensBlockNote(holdings: List<TokenHolding>): String? {
        val shown = holdings.mapNotNull { it.reading as? TokenBalanceReading.Verified }.filter { !it.units.isZero }
        if (shown.isEmpty()) return null
        val newest = shown.mapNotNull { it.blockNumber }.maxOrNull()
        return if (newest != null) "Tokens verified at block ${grouped(newest)}" else "Tokens verified"
    }

    private companion object {
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
        const val NATIVE_DECIMALS = 18
    }
}
