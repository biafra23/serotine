package eth.serotine.ui.tokens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Tokens settings' ViewModel: every known token, each hidden, shown again or (if added) removed. */
class TokensViewModel(
    observeKnownTokens: ObserveKnownTokens,
    private val setTokenHidden: SetTokenHidden,
    private val removeToken: RemoveToken,
    /** The network this screen works on: the only one until the network steps (docs/networks.md, part 3). */
    /** The network whose tokens are listed: the light-client strip is its. */
    val network: Network,
) : ViewModel() {

    private val lastChange = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TokensUiState> = combine(observeKnownTokens(network), lastChange) { known, change ->
        TokensUiState(
            items = known.tokens.map(::item),
            notice = when {
                known.settingsUnavailable -> SETTINGS_UNAVAILABLE
                change != null -> change
                known.settingsSetAside -> SETTINGS_SET_ASIDE
                else -> null
            },
            canChange = !known.settingsUnavailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TokensUiState())

    /** Hides the token at [address] from every account, or shows it again. */
    fun setHidden(address: String, hidden: Boolean) = change(address) { setTokenHidden(network, it, hidden) }

    /** Removes the added token at [address]. */
    fun remove(address: String) = change(address) { removeToken(network, it) }

    private fun change(address: String, action: suspend (Address) -> TokenChange) {
        val token = Address.parse(address) ?: return
        viewModelScope.launch {
            lastChange.value = when (action(token)) {
                TokenChange.NotSaved -> "The change could not be saved. Nothing has changed."
                else -> null
            }
        }
    }

    private fun item(known: KnownToken) = TokenItem(
        symbol = displaySymbol(known.token.symbol),
        name = displayName(known.token.name),
        address = Eip55.format(known.token.address),
        icon = known.token.icon,
        builtIn = known.origin == TokenOrigin.BUILT_IN,
        status = status(known),
        problem = known.check is BuiltInCheck.Disagrees,
        hidden = known.hidden,
        warning = if (known.sharesBuiltInSymbol) {
            "Not the built-in ${displaySymbol(known.token.symbol)}: another contract"
        } else {
            null
        },
    )

    private fun status(known: KnownToken): String = when (val check = known.check) {
        null -> "Added: as its contract describes itself"
        BuiltInCheck.Checking -> "Built in · checking its contract…"
        is BuiltInCheck.Agrees ->
            "Built in · checked against its contract" + (check.blockNumber?.let { " at block ${grouped(it)}" } ?: "")
        is BuiltInCheck.Disagrees ->
            if (check.saidSymbol == null) {
                "Not shown: its contract does not answer as a token"
            } else {
                "Not shown: its contract says ${check.saidSymbol} with ${check.saidDecimals} decimals"
            }
        is BuiltInCheck.Unavailable ->
            if (check.reason == ReadUnavailable.REFUSED) {
                "Built in · its contract cannot be checked on this device"
            } else {
                "Built in · its contract cannot be checked right now"
            }
    }

    private companion object {
        const val SETTINGS_SET_ASIDE =
            "Your token settings were damaged and have been set aside: the tokens you added and hid start over."
        const val SETTINGS_UNAVAILABLE =
            "Your token settings cannot be read right now, so nothing can be added, hidden or removed. " +
                "Serotine keeps trying."
    }
}
