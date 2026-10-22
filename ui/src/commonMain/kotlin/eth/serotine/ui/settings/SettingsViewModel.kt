package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.NetworkOverview
import eth.serotine.domain.usecase.ObserveDebugScreen
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNetworkOverview
import eth.serotine.domain.usecase.SetDebugScreen
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.platform.ScreenCaptureDebugSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings screen's ViewModel: the display currency, the known tokens, the networks with their
 * switches and light clients (docs/networks.md, N16 and N20), the Debug tab's switch and the
 * version; and, in a debug build, the switch that allows screen recordings of the protected
 * screens. Turning a network on asks nothing; turning off one with accounts asks first, and one
 * without turns off at once. The tokens and the currency are [network]'s, the only one there is
 * until the network steps.
 */
class SettingsViewModel(
    private val observeNetworkOverview: ObserveNetworkOverview,
    private val setNetworkEnabled: SetNetworkEnabled,
    observeKnownTokens: ObserveKnownTokens,
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    network: Network,
    /** The debug build's screen-capture switch; null in a release build, which offers none. */
    private val screenCapture: ScreenCaptureDebugSwitch?,
    observeDebugScreen: ObserveDebugScreen,
    private val setDebugScreen: SetDebugScreen,
    /** Now, in unix seconds: what the light clients' lines date the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** Whether the last switch of the Debug tab could not be saved. */
    private val debugScreenNotSaved = MutableStateFlow(false)

    /** The networks whose last switch could not be saved. */
    private val networkNotSaved = MutableStateFlow(emptySet<Network>())

    /** The confirmation asked before a network is turned off, while it is asked. */
    private val turningOff = MutableStateFlow<TurnOffConfirmation?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            combine(observeNetworkOverview(), networkNotSaved, turningOff, ::Triple),
            observeKnownTokens(network),
            observeDisplayCurrencies(network),
            screenCapture?.allowed ?: flowOf(null),
            combine(observeDebugScreen(), debugScreenNotSaved, ::Pair),
        ) { (networks, notSaved, confirming), known, currencies, captureAllowed, (debugScreen, debugNotSaved) ->
            val builtIn = known.tokens.count { it.origin == TokenOrigin.BUILT_IN }
            val added = if (known.settingsUnavailable) {
                "added ones cannot be read"
            } else {
                "${known.tokens.size - builtIn} added"
            }
            val currency = currencies.chosen.code + when {
                currencies.unreadable -> " (your choice cannot be read)"
                currencies.setAside -> " (your saved choice was damaged and set aside)"
                else -> ""
            }
            SettingsUiState(
                networks = networks.map { rowOf(it, it.network in notSaved) },
                version = APP_VERSION,
                tokens = "$builtIn built in · $added",
                currency = currency,
                screenCaptureAllowed = captureAllowed,
                debugScreen = debugScreen,
                debugScreenNotSaved = debugNotSaved,
                turningOff = confirming,
            )
        }
            .stateIn(
                viewModelScope,
                // A tab back after the timeout must not show an old head as current.
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                SettingsUiState(emptyList(), APP_VERSION, screenCaptureAllowed = screenCapture?.allowed?.value),
            )

    private fun rowOf(overview: NetworkOverview, notSaved: Boolean): NetworkRow {
        val accounts = overview.accounts
        val count = when {
            accounts == null -> "Accounts cannot be read"
            accounts.isEmpty() -> "No account"
            accounts.size == 1 -> "1 account"
            else -> "${accounts.size} accounts"
        }
        val item = LightClientItem.from(overview.status, clock())
        return NetworkRow(
            chainId = overview.network.chainId,
            name = overview.network.displayName,
            on = overview.on,
            line = if (overview.on) "$count · ${item.text}" else "$count · Off",
            health = if (overview.on) item.health else LightClientHealth.NOT_RUNNING,
            testnet = overview.network.testnet,
            notSaved = notSaved,
        )
    }

    /**
     * Turns the network of [chainId] on at once, or off: at once when no account is on it, after
     * the confirmation otherwise (N20). A switch that cannot be saved leaves the row as it was,
     * saying so.
     */
    fun switchNetwork(chainId: Long, on: Boolean) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            if (!on) {
                val overview = observeNetworkOverview().first().first { it.network == network }
                if (!overview.accounts.isNullOrEmpty()) {
                    turningOff.value = confirmationOf(overview)
                    return@launch
                }
            }
            save(network, on)
        }
    }

    /** Turns off the network the confirmation asked about. */
    fun confirmTurnOff() {
        val asked = turningOff.value ?: return
        turningOff.value = null
        val network = Network.ofChainId(asked.chainId) ?: return
        viewModelScope.launch { save(network, on = false) }
    }

    /** Leaves the network on: the confirmation is dismissed. */
    fun cancelTurnOff() {
        turningOff.value = null
    }

    private suspend fun save(network: Network, on: Boolean) {
        val saved = setNetworkEnabled(network, on)
        networkNotSaved.value = if (saved) networkNotSaved.value - network else networkNotSaved.value + network
    }

    /** Allows screen recordings of the protected screens, or blocks them again; a release build has no switch. */
    fun allowScreenCapture(allowed: Boolean) {
        screenCapture?.allow(allowed)
    }

    /**
     * Shows the Debug tab, or hides it, which empties its log. A switch that cannot be saved
     * leaves the row as it was, saying so.
     */
    fun showDebugScreen(shown: Boolean) {
        viewModelScope.launch { debugScreenNotSaved.value = !setDebugScreen(shown) }
    }

    internal companion object {
        /** The question before [overview]'s network, which has accounts, is turned off (N20). */
        fun confirmationOf(overview: NetworkOverview): TurnOffConfirmation {
            val name = overview.network.displayName
            val accounts = overview.accounts.orEmpty()
            val stay = when (accounts.size) {
                1 -> "Its account, ${accounts[0].label}, stays in your list."
                2, 3 -> "Its ${accounts.size} accounts, ${names(accounts.map { it.label })}, stay in your list."
                else -> "Its ${accounts.size} accounts stay in your list."
            }
            val waiting = listOfNotNull(
                when (overview.unconfirmed) {
                    0 -> null
                    1 -> "A transaction sent from it is not confirmed yet."
                    else -> "${overview.unconfirmed} transactions sent from it are not confirmed yet."
                },
                when (overview.unlistedSafes) {
                    0 -> null
                    1 -> "A Safe deployed on it is not listed yet."
                    else -> "${overview.unlistedSafes} Safes deployed on it are not listed yet."
                },
            )
            return TurnOffConfirmation(
                chainId = overview.network.chainId,
                title = "Turn off $name?",
                text = "$stay Its balances are not checked and nothing can be sent from it until you turn " +
                    "$name on again.",
                waiting = waiting.takeIf { it.isNotEmpty() }
                    ?.joinToString(" ", postfix = " Following it waits until $name is on again; nothing is lost."),
            )
        }

        /** "Main", "Main and Savings", "Main, Savings and Daily". */
        private fun names(labels: List<String>): String =
            if (labels.size <= 1) labels.joinToString() else labels.dropLast(1).joinToString() + " and " + labels.last()
    }
}
