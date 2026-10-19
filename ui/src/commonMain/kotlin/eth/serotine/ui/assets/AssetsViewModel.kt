package eth.serotine.ui.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.domain.usecase.Portfolio
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.totalNote
import eth.serotine.ui.components.totalText
import eth.serotine.ui.platform.formatDateTime
import eth.serotine.ui.update.UpgradeBannerItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The Assets screen's ViewModel: turns the domain's portfolio — the accounts, what each
 * holds and what it is worth ([ObservePortfolio]) — the light client's state and any network
 * upgrade this version does not support into [AssetsUiState], on the [network] the
 * composition root runs the app on. It holds no platform reference and exposes state only
 * through [uiState]; what the user does with an account is navigation, which the screen's route
 * turns into the destination of the screen or dialog that does it.
 */
class AssetsViewModel(
    observePortfolio: ObservePortfolio,
    observeSafes: ObserveSafes,
    observeLightClient: ObserveLightClient,
    observeUpgradeWarning: ObserveUpgradeWarning,
    private val network: Network,
    /** Now, in unix seconds: what the strip dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
    /** How the banner dates an upgrade, and a row a balance last verified (unix seconds). */
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    private val strip = observeLightClient().asItems(clock)

    /** The upgrade banner, none until the first warning: Assets never waits for it. */
    private val upgrade = observeUpgradeWarning()
        .map { UpgradeBannerItem.from(it, network, formatTime) }
        .onStart { emit(null) }

    // The checksums once per account, not on every balance reading.
    private val checksums = mutableMapOf<Address, String>()

    val uiState: StateFlow<AssetsUiState> = combine(observePortfolio(), observeSafes(), strip, upgrade, ::stateOf)
        .stateIn(
            scope = viewModelScope,
            // Keeps collecting across a configuration change (the screen re-subscribes within
            // the timeout) but stops when the screen is really gone, and then forgets the
            // last state: a screen back after that must not show old balances as current.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = AssetsUiState.Loading(
                network.displayName,
                LightClientItem.from(LightClientStatus.Starting, now = 0),
            ),
        )

    private fun stateOf(
        portfolio: Portfolio,
        safes: Map<Address, SafeReading>,
        lightClient: LightClientItem,
        upgrade: UpgradeBannerItem?,
    ): AssetsUiState =
        when (val list = portfolio.accounts) {
            is AccountList.Unreadable -> AssetsUiState.ListUnavailable(
                network.displayName,
                lightClient,
                when (list.reason) {
                    AccountList.Reason.NEWER_FORMAT -> NEWER_FORMAT
                    AccountList.Reason.READ_FAILED -> READ_FAILED
                },
                upgrade,
            )
            is AccountList.Loaded -> {
                // A row from the start, "Checking…" until its first reading: an account just
                // added, or a screen just back, never waits for every proof.
                val items = list.accounts.map { account ->
                    val value = portfolio.values[account.address]
                    // In the currency the value was computed in: just after a change of
                    // currency, an account not revalued yet is still in the one before.
                    AccountItem.from(
                        account,
                        value?.native,
                        network,
                        checksums.getOrPut(account.address) { Eip55.format(account.address) },
                        value?.let { it.nativeValue to it.currency },
                        safes[account.address],
                        formatTime,
                    )
                }
                val total = portfolio.total.takeIf { list.accounts.isNotEmpty() }
                AssetsUiState.Ready(
                    network.displayName,
                    lightClient,
                    items,
                    notice = if (list.damagedListSetAside) DAMAGED_SET_ASIDE else null,
                    total = total?.let { totalText(it, portfolio.currency) },
                    totalNote = total?.let { totalNote(it, network, portfolio.currency) }?.ifEmpty { null },
                    upgrade = upgrade,
                )
            }
        }

    private companion object {
        const val DAMAGED_SET_ASIDE =
            "Your saved account list was damaged and has been set aside. Add your accounts again."
        const val NEWER_FORMAT =
            "Your account list was saved by a newer version of Serotine. Update the app to see it."
        const val READ_FAILED =
            "Your saved account list cannot be read right now. Nothing has been changed, and no " +
                "account can be added until it can be read. Serotine keeps trying."
    }
}
