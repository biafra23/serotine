package eth.serotine.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.EnsNameCheck
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.Portfolio
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.domain.usecase.UpgradeWarnings
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.StaleAnchorWarning
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.notTurnedOn
import eth.serotine.ui.components.spokenList
import eth.serotine.ui.components.totalNote
import eth.serotine.ui.components.totalText
import eth.serotine.ui.platform.formatDateTime
import eth.serotine.ui.update.UpgradeBannerItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Accounts screen's ViewModel: turns the domain's portfolio — the accounts, what each
 * holds and what it is worth ([ObservePortfolio]), whether the ENS name an account's name
 * follows still points to it ([ObserveEnsNames]) — each network's light client and whether it
 * is on, and each network's upgrade this version does not support into [AccountsUiState]: a
 * section per network with accounts (docs/networks.md, N1, N2 and N18), a banner per network with
 * an upgrade, and a network blocked alone said in its own section, its accounts' Send greyed out
 * (*Upgrades and the update screen*). A network that cannot price the display currency says so
 * in its section and stays out of the total (*Values*). It holds no
 * platform reference and exposes state only through [uiState]; what the user does with an
 * account is navigation, which the screen's route turns into the destination of the screen or
 * dialog that does it, except turning a network on again ([turnOnNetwork]) and letting a light
 * client parked on a stale checkpoint catch up anyway ([syncPastStaleAnchor]; owner's request,
 * 2026-10-24), which its section warns of ([NetworkSection.staleAnchor]).
 */
class AccountsViewModel(
    observePortfolio: ObservePortfolio,
    observeSafes: ObserveSafes,
    observeEnsNames: ObserveEnsNames,
    observeLightClient: ObserveLightClient,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    observeUpgradeWarnings: ObserveUpgradeWarnings,
    private val syncPastStaleAnchor: SyncPastStaleAnchor,
    /** Now, in unix seconds: what the sections date their head blocks against. */
    private val clock: () -> Long = ::epochSecondsNow,
    /** How the banner dates an upgrade, and a row a balance last verified (unix seconds). */
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    /** Each network's light client, in `Network` order. */
    private val statuses = combine(Network.entries.map { observeLightClient(it).asItems(clock) }) { it.toList() }

    /** Each network's stale-checkpoint warning, in `Network` order: null while it is not refusing to sync. */
    private val staleAnchors = combine(
        Network.entries.map { network ->
            observeLightClient(network)
                .map { status ->
                    (status as? LightClientStatus.StaleAnchor)?.let { StaleAnchorWarning.of(network, it) }
                }
                .distinctUntilChanged()
        },
    ) { it.toList() }

    /** Each network's upgrade warning, none until the first: Accounts never waits for them. */
    private val upgrades = observeUpgradeWarnings().onStart { emit(UpgradeWarnings(emptyMap(), emptySet())) }

    // The checksums once per account, not on every balance reading.
    private val checksums = mutableMapOf<Address, String>()

    // Accounts never waits for a name's check: until it comes, a name stands as it was verified at Watch.
    private val ensNames = observeEnsNames().onStart { emit(emptyMap()) }

    /** What the screen says once, until it has: a network that could not be turned on. */
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountsUiState> = combine(
        observePortfolio(),
        combine(observeSafes(), ensNames, ::Pair),
        combine(statuses, staleAnchors, observeEnabledNetworks(), ::Triple),
        upgrades,
        message,
    ) { portfolio, (safes, ensNames), (lightClients, staleAnchors, on), upgrades, message ->
        stateOf(portfolio, safes, ensNames, lightClients, staleAnchors, on.toSet(), upgrades).let { state ->
            if (state is AccountsUiState.Ready) state.copy(message = message) else state
        }
    }
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone, and then forgets the
            // last state: a screen back after that must not show old balances as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = AccountsUiState.Loading(),
        )

    /** Turns the network of [chainId] on again: the way out of a greyed-out action on one that is off (N19). */
    fun turnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch {
            // A setting that cannot be saved leaves the network off, and says so.
            if (!setNetworkEnabled(network, on = true)) message.value = notTurnedOn(network)
        }
    }

    /**
     * Lets the light client of [chainId]'s network catch up from its stale checkpoint anyway, for
     * this run of the app: what the user confirmed in its section's dialog.
     */
    fun syncPastStaleAnchor(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { syncPastStaleAnchor(network) }
    }

    /** The screen has said [AccountsUiState.Ready.message]. */
    fun messageShown() {
        message.value = null
    }

    private fun stateOf(
        portfolio: Portfolio,
        safes: Map<AccountId, SafeReading>,
        ensNames: Map<AccountId, EnsNameCheck>,
        lightClients: List<LightClientItem>,
        staleAnchors: List<StaleAnchorWarning?>,
        on: Set<Network>,
        warnings: UpgradeWarnings,
    ): AccountsUiState {
        // A network that is off shows no banner, even with an upgrade recorded (docs/networks.md).
        val upgrade = Network.entries.filter { it in on }
            .mapNotNull { UpgradeBannerItem.from(warnings.of(it), it, formatTime) }
        return when (val list = portfolio.accounts) {
            is AccountList.Unreadable -> AccountsUiState.ListUnavailable(
                when (list.reason) {
                    AccountList.Reason.NEWER_FORMAT -> NEWER_FORMAT
                    AccountList.Reason.READ_FAILED -> READ_FAILED
                },
                upgrade,
            )
            is AccountList.Loaded -> {
                val currency = portfolio.currency
                val sections = Network.entries.mapIndexedNotNull { i, network ->
                    val accounts = list.accounts.filter { it.network == network }
                    if (accounts.isEmpty()) return@mapIndexedNotNull null
                    val isOn = network in on
                    // A network this version can no longer follow, while others still can (the update
                    // screen is for when none can): said in its section, its accounts' Send greyed out.
                    val blocked = (warnings.of(network) as? UpgradeWarning.UpdateRequired)
                        ?.let { networkBlockedReason(network.displayName) }
                        ?.takeIf { isOn }
                    // A row from the start, "Checking…" until its first reading: an account just
                    // added, or a screen just back, never waits for every proof.
                    val items = accounts.map { account ->
                        val value = portfolio.values[account.id]
                        // In the currency the value was computed in: just after a change of
                        // currency, an account not revalued yet is still in the one before.
                        AccountItem.from(
                            account,
                            value?.native,
                            account.network,
                            checksums.getOrPut(account.address) { Eip55.format(account.address) },
                            value?.let { it.nativeValue to it.currency },
                            safes[account.id],
                            formatTime,
                            ensNames[account.id],
                            networkOn = isOn,
                        ).let { item -> if (blocked != null) item.copy(networkBlocked = blocked) else item }
                    }
                    val total = portfolio.totalOn(network)
                    NetworkSection(
                        chainId = network.chainId,
                        name = network.displayName,
                        testnet = network.testnet,
                        on = isOn,
                        status = if (isOn) lightClients[i] else OFF,
                        accounts = items,
                        total = if (isOn) totalText(total, currency) else "Off",
                        totalNote = sectionNote(total, network, currency, portfolio).takeIf { isOn }?.ifEmpty { null },
                        blocked = blocked,
                        staleAnchor = staleAnchors[i].takeIf { isOn },
                    )
                }
                // The top total adds the real networks that are on; a testnet only when the wallet has
                // no account on a real network at all, on or off (docs/networks.md, N1).
                val held = Network.entries.filter { network -> list.accounts.any { it.network == network } }
                val counted = held.filterNot { it.testnet }.ifEmpty { held }
                    .filter { it in on && it !in portfolio.unpricedOn }
                val total = counted.fold(Total.NONE) { sum, network -> sum + portfolio.totalOn(network) }
                // A testnet left out of the total, said so; one that is off says it in its own section.
                val leftOut = sections.filter { it.testnet && it.on && counted.none { n -> n.testnet } }.map { it.name }
                AccountsUiState.Ready(
                    sections,
                    notice = if (list.damagedListSetAside) DAMAGED_SET_ASIDE else null,
                    total = counted.firstOrNull()?.let { totalText(total, currency) },
                    totalLabel = counted.takeIf { it.isNotEmpty() }
                        ?.let { "Total · ${currency.code} · ${spokenList(it.map { n -> n.displayName })}" },
                    totalNote = counted.firstOrNull()?.let { first ->
                        val unpriced = held.filter { it in on && it in portfolio.unpricedOn }
                            .map { "${it.displayName} has no ${currency.code} rate" }
                        (listOf(totalNote(total, first, currency)) + leftOut.map { "$it not included" } + unpriced)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                            .ifEmpty { null }
                    },
                    upgrades = upgrade,
                )
            }
        }
    }

    /**
     * What a network's section says under its total: [totalNote]'s, or that [network] cannot price
     * the display currency, which leaves its accounts out of the total (docs/networks.md, *Values*).
     */
    private fun sectionNote(total: Total, network: Network, currency: DisplayCurrency, portfolio: Portfolio): String =
        if (network in portfolio.unpricedOn) {
            "No ${currency.code} rate on ${network.displayName} · not in the total"
        } else {
            totalNote(total, network, currency)
        }

    private companion object {
        /** A network's status line while it is off in this wallet (N18). */
        val OFF =
            LightClientItem("Off in this wallet · nothing is checked", null, false, LightClientHealth.NOT_RUNNING)

        const val DAMAGED_SET_ASIDE =
            "Your saved account list was damaged and has been set aside. Add your accounts again."
        const val NEWER_FORMAT =
            "Your account list was saved by a newer version of Serotine. Update the app to see it."
        const val READ_FAILED =
            "Your saved account list cannot be read right now. Nothing has been changed, and no " +
                "account can be added until it can be read. Serotine keeps trying."
    }
}
