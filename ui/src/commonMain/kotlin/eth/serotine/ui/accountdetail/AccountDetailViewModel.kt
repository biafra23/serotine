package eth.serotine.ui.accountdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.totalNote
import eth.serotine.ui.components.totalText
import eth.serotine.ui.components.valuationText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The account detail screen's ViewModel: the account under [address], what it holds and what
 * that is worth in the display currency ([ObserveAccountValue]), on [network], as
 * [AccountDetailUiState]. Its intents are the order of the rows ([sortBy]) and hiding a token
 * ([hideToken], said through [messageShown]); what else the user does from the screen is
 * navigation (Receive, Rename, Remove) or the clipboard, which the route handles.
 */
class AccountDetailViewModel(
    address: Address,
    observeAccount: ObserveAccount,
    observeAccountValue: ObserveAccountValue,
    private val setTokenHidden: SetTokenHidden,
    private val network: Network,
) : ViewModel() {

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(address)

    /** What the last Hide token did, until the screen has said it. */
    private val message = MutableStateFlow<String?>(null)

    private val sort = MutableStateFlow(AssetSort.VALUE)

    val uiState: StateFlow<AccountDetailUiState> =
        combine(observeAccount(address), observeAccountValue(address), sort, message) { lookup, value, order, said ->
            when (lookup) {
                is AccountLookup.Found -> AccountDetailUiState.Ready(
                    label = lookup.account.label,
                    address = display,
                    kind = lookup.account.kind,
                    control = Control.of(lookup.account.kind, lookup.account.custody),
                    total = totalText(value.total, value.currency),
                    totalNote = totalNote(value.total, network).ifEmpty { null },
                    assets = sorted(rows(value), order),
                    sort = order,
                    tokensNote = tokensNote(value),
                    pricesNote = PRICES + if (network.testnet) ", testnet prices" else "",
                    message = said,
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

    /** Orders the rows by [order]. */
    fun sortBy(order: AssetSort) {
        sort.value = order
    }

    /**
     * Hides the token at [contract], shown as [symbol], from every account; Settings → Tokens
     * shows it again. How that went is the state's [AccountDetailUiState.Ready.message].
     */
    fun hideToken(contract: String, symbol: String) {
        val token = Address.parse(contract) ?: return
        viewModelScope.launch {
            message.value = when (setTokenHidden(token, hidden = true)) {
                TokenChange.Changed, TokenChange.AlreadyPresent -> "$symbol hidden. Settings → Tokens shows it again."
                TokenChange.NotFound, TokenChange.NotSaved ->
                    "$symbol could not be hidden: the token settings cannot be saved right now."
            }
        }
    }

    /** The screen has said [AccountDetailUiState.Ready.message]. */
    fun messageShown() {
        message.value = null
    }

    /**
     * The native row and a row per token held: a token whose verified balance is zero is not
     * held. Each row names the block its own amount was verified at: the readings are polled
     * one by one and need not share one.
     */
    private fun rows(value: AccountValue): List<Row> {
        val native = value.native
        val nativeRow = Row(
            AssetRow(
                symbol = network.nativeSymbol,
                name = network.nativeName,
                amount = native?.let { balanceText(it, network) } ?: "Checking…",
                icon = network.nativeIcon,
                verified = native is BalanceReading.Verified,
                verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
                value = valuationText(value.nativeValue, value.currency),
            ),
            value.nativeValue,
        )
        val tokenRows = value.tokens.mapNotNull { (holding, valuation) ->
            val verified = holding.reading as? TokenBalanceReading.Verified ?: return@mapNotNull null
            if (verified.units.isZero) return@mapNotNull null
            val token = holding.token
            val symbol = displaySymbol(token.symbol)
            Row(
                AssetRow(
                    symbol = symbol,
                    name = displayName(token.name),
                    amount = formatUnits(verified.units.decimal, token.decimals, symbol),
                    icon = token.icon,
                    verified = true,
                    verifiedAt = verified.blockNumber?.let(::block),
                    value = valuationText(valuation, value.currency),
                    contract = Eip55.format(token.address),
                    warning = if (holding.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
                ),
                valuation,
            )
        }
        return listOf(nativeRow) + tokenRows
    }

    /** A row and the value it is sorted by. */
    private class Row(val asset: AssetRow, val value: Valuation?)

    private fun sorted(rows: List<Row>, order: AssetSort): List<AssetRow> {
        val byName = compareBy<Row> { it.asset.symbol.lowercase() }
        return when (order) {
            AssetSort.NAME -> rows.sortedWith(byName)
            AssetSort.VALUE -> {
                val (priced, others) = rows.partition { it.value is Valuation.Valued }
                priced.sortedByDescending { (it.value as Valuation.Valued).amount } + others.sortedWith(byName)
            }
        }.map { it.asset }
    }

    /**
     * What is not known (yet) about the tokens no row shows: still being checked, or why not;
     * and that the user's token settings cannot be read, so the rows ignore them.
     */
    private fun tokensNote(value: AccountValue): String? {
        val readings = value.tokens.map { it.holding.reading }
        val checking = readings.count { it == null || it == TokenBalanceReading.Syncing }
        val refused = readings.count { (it as? TokenBalanceReading.Unavailable)?.reason == ReadUnavailable.REFUSED }
        val unverified = readings.count { it is TokenBalanceReading.Unavailable } - refused
        val notTokens = readings.count { it == TokenBalanceReading.NotAToken }
        val parts = listOfNotNull(
            checking.takeIf { it > 0 }?.let { "checking ${tokens(it)}" },
            unverified.takeIf { it > 0 }?.let { "${tokens(it)} not verified" },
            refused.takeIf { it > 0 }?.let { "${tokens(it)} cannot be read on this device" },
            notTokens.takeIf { it > 0 }?.let { "${tokens(it)} did not answer as a token" },
            SETTINGS_UNAVAILABLE.takeIf { value.settingsUnavailable },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
    }

    private fun tokens(count: Int) = if (count == 1) "1 token" else "$count tokens"

    private fun block(number: Long) = "block ${grouped(number)}"

    private companion object {
        const val PRICES = "Prices: Chainlink feeds, read through the light client"
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
        const val SETTINGS_UNAVAILABLE =
            "your token settings cannot be read right now: hidden tokens show, added ones are missing"
    }
}
