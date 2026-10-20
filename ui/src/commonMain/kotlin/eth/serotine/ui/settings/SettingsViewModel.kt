package eth.serotine.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.MeteredDownloadsChoice
import eth.serotine.domain.usecase.AllowHeavyDownloadsOnMetered
import eth.serotine.domain.usecase.HeavyDownloads
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveHeavyDownloads
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.TokenOrigin
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.asItems
import eth.serotine.ui.components.epochSecondsNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Settings screen's ViewModel: the display currency, the known tokens, the [network], the
 * heavy downloads on a metered connection, the light client's state and the version.
 */
class SettingsViewModel(
    observeLightClient: ObserveLightClient,
    observeKnownTokens: ObserveKnownTokens,
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    observeHeavyDownloads: ObserveHeavyDownloads,
    private val allowOnMetered: AllowHeavyDownloadsOnMetered,
    network: Network,
    /** Now, in unix seconds: what the light client's line dates the head block against. */
    private val clock: () -> Long = ::epochSecondsNow,
) : ViewModel() {

    /** The last choice of the heavy downloads that could not be saved, null once one is. */
    private val notSaved = MutableStateFlow<Boolean?>(null)

    /** One save at a time, in the order the user made them, so the last one's outcome is what [notSaved] says. */
    private val saving = Mutex()

    val uiState: StateFlow<SettingsUiState> =
        combine(
            observeLightClient().asItems(clock),
            observeKnownTokens(),
            observeDisplayCurrencies(),
            observeHeavyDownloads(),
            notSaved,
        ) { item, known, currencies, downloads, notSaved ->
            val builtIn = known.tokens.count { it.origin == TokenOrigin.BUILT_IN }
            val added = if (known.settingsUnavailable) {
                "added ones cannot be read"
            } else {
                "${known.tokens.size - builtIn} added"
            }
            val tokens = "$builtIn built in · $added"
            val currency = currencies.chosen.code + when {
                currencies.unreadable -> " (your choice cannot be read)"
                currencies.setAside -> " (your saved choice was damaged and set aside)"
                else -> ""
            }
            SettingsUiState(network.displayName, item, APP_VERSION, tokens, currency, downloads.item(notSaved))
        }
        .stateIn(
            viewModelScope,
            // A tab back after the timeout must not show an old head as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            SettingsUiState(network.displayName, LightClientItem.from(LightClientStatus.Starting, 0), APP_VERSION),
        )

    /** Allows the heavy downloads on a metered connection, or not; a choice not saved is said on the switch. */
    fun allowHeavyDownloadsOnMetered(allowed: Boolean) {
        viewModelScope.launch {
            saving.withLock { notSaved.value = allowed.takeUnless { allowOnMetered(allowed) } }
        }
    }

    /** The switch for these downloads; [notSaved] the choice not saved, said while it is not the one held. */
    private fun HeavyDownloads.item(notSaved: Boolean?): MeteredDownloadsItem {
        if (cost == ConnectionCost.NOT_REPORTED) {
            return MeteredDownloadsItem(
                on = allowedOnMetered,
                text = "This device does not say whether its connection is metered: the account history is " +
                    "walked back on any connection",
                unavailableReason = "This device does not report whether its connection is metered, so Serotine " +
                    "takes it as unmetered and walks the account history back on any connection. There is no " +
                    "metered connection here to allow it on.",
            )
        }
        val unreadable = choice is MeteredDownloadsChoice.Unreadable
        val text = buildString {
            append(if (allowedOnMetered) METERED_DOWNLOADS_ON else METERED_DOWNLOADS_OFF)
            when {
                unreadable -> append(" (your choice cannot be read)")
                (choice as? MeteredDownloadsChoice.Chosen)?.setAside == true ->
                    append(" (your saved choice was damaged and set aside)")
            }
            if (notSaved != null && notSaved != allowedOnMetered) append(" · Your choice could not be saved")
            // A connection that cannot be read waits as a metered one does.
            if (!mayRun) append(" · Waiting now: no unmetered connection")
        }
        val reason = if (unreadable) {
            "Your saved choice cannot be read, so heavy downloads are taken as not allowed on a metered " +
                "connection. The app keeps trying to read it, and the switch works again once it can."
        } else {
            null
        }
        return MeteredDownloadsItem(allowedOnMetered, text, reason)
    }
}
