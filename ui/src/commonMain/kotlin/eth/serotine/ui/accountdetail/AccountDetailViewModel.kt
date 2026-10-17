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
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.TokenHolding
import eth.serotine.domain.usecase.TokenHoldings
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The account detail screen's ViewModel: the account under [address], its verified native
 * balance on [network] and what it holds of each known token, as [AccountDetailUiState]. Its
 * one intent is hiding a token ([hideToken], said through [messageShown]); what else the user
 * does from the screen is navigation (Receive, Rename, Remove) or the clipboard, which the
 * route handles.
 */
class AccountDetailViewModel(
    address: Address,
    observeAccount: ObserveAccount,
    observeNativeBalance: ObserveNativeBalance,
    observeTokenHoldings: ObserveTokenHoldings,
    private val setTokenHidden: SetTokenHidden,
    private val network: Network,
) : ViewModel() {

    private val balance = observeNativeBalance(address)
        .map<BalanceReading, BalanceReading?> { it }
        .onStart { emit(null) }

    // Like the balance, it never holds the screen back: the header shows before the catalog does.
    private val holdings = observeTokenHoldings(address).onStart { emit(TokenHoldings(emptyList())) }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(address)

    /** What the last Hide token did, until the screen has said it. */
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountDetailUiState> =
        combine(observeAccount(address), balance, holdings, message) { lookup, reading, held, said ->
            when (lookup) {
                is AccountLookup.Found -> AccountDetailUiState.Ready(
                    label = lookup.account.label,
                    address = display,
                    kind = lookup.account.kind,
                    control = Control.of(lookup.account.kind, lookup.account.custody),
                    balance = reading?.let { balanceText(it, network) } ?: "Checking…",
                    balanceVerified = reading is BalanceReading.Verified,
                    balanceNote = (reading as? BalanceReading.Verified)?.let { "Verified at ${block(it.blockNumber)}" },
                    assets = assets(reading, held.holdings),
                    tokensNote = tokensNote(held),
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

    /**
     * The native row first, then a row per token held, by symbol: a token whose verified
     * balance is zero is not held. Each row names the block its own amount was verified at:
     * the readings are polled one by one and need not share one. (The order by value comes
     * with prices.)
     */
    private fun assets(native: BalanceReading?, holdings: List<TokenHolding>): List<AssetRow> {
        val nativeRow = AssetRow(
            symbol = network.nativeSymbol,
            name = network.nativeName,
            amount = native?.let { balanceText(it, network) } ?: "Checking…",
            icon = network.nativeIcon,
            verified = native is BalanceReading.Verified,
            verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
        )
        val tokenRows = holdings.mapNotNull { holding ->
            val verified = holding.reading as? TokenBalanceReading.Verified ?: return@mapNotNull null
            if (verified.units.isZero) return@mapNotNull null
            val token = holding.token
            AssetRow(
                symbol = token.symbol,
                name = token.name,
                amount = formatUnits(verified.units.decimal, token.decimals, token.symbol),
                icon = token.icon,
                verified = true,
                verifiedAt = verified.blockNumber?.let(::block),
                contract = Eip55.format(token.address),
                warning = "Not the built-in ${token.symbol}: another contract".takeIf { holding.sharesBuiltInSymbol },
            )
        }
        return listOf(nativeRow) + tokenRows.sortedBy { it.symbol.lowercase() }
    }

    /**
     * What is not known (yet) about the tokens no row shows: still being checked, or why not;
     * and that the user's token settings cannot be read, so the rows ignore them.
     */
    private fun tokensNote(held: TokenHoldings): String? {
        val readings = held.holdings.map { it.reading }
        val checking = readings.count { it == null || it == TokenBalanceReading.Syncing }
        val refused = readings.count { (it as? TokenBalanceReading.Unavailable)?.reason == ReadUnavailable.REFUSED }
        val unverified = readings.count { it is TokenBalanceReading.Unavailable } - refused
        val notTokens = readings.count { it == TokenBalanceReading.NotAToken }
        val parts = listOfNotNull(
            checking.takeIf { it > 0 }?.let { "checking ${tokens(it)}" },
            unverified.takeIf { it > 0 }?.let { "${tokens(it)} not verified" },
            refused.takeIf { it > 0 }?.let { "${tokens(it)} cannot be read on this device" },
            notTokens.takeIf { it > 0 }?.let { "${tokens(it)} did not answer as a token" },
            SETTINGS_UNAVAILABLE.takeIf { held.settingsUnavailable },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar { it.uppercase() }
    }

    private fun tokens(count: Int) = if (count == 1) "1 token" else "$count tokens"

    /**
     * Hides the token at [contract], shown as [symbol], from every account; Settings → Tokens
     * shows it again. How that went is the state's [AccountDetailUiState.Ready.message].
     */
    fun hideToken(contract: String, symbol: String) {
        val address = Address.parse(contract) ?: return
        viewModelScope.launch {
            message.value = when (setTokenHidden(address, hidden = true)) {
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

    private fun block(number: Long) = "block ${grouped(number)}"

    private companion object {
        const val LIST_UNAVAILABLE = "Your account list cannot be read right now. Serotine keeps trying."
        const val SETTINGS_UNAVAILABLE =
            "your token settings cannot be read right now: hidden tokens show, added ones are missing"
    }
}
