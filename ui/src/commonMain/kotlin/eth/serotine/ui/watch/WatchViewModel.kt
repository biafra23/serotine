package eth.serotine.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.usecase.AccountLookup
import eth.serotine.domain.usecase.AddressWatch
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Watch address screen's ViewModel: reads the typed text as an address (EIP-55 checked at
 * the input), shows what the light client proves is at it, and adds it through
 * [WatchAddress], which checks again before it adds.
 */
class WatchViewModel(
    observeAddressKind: ObserveAddressKind,
    observeAccount: ObserveAccount,
    private val watchAddress: WatchAddress,
) : ViewModel() {

    private class Typed(val address: String = "", val name: String = "")

    private class Outcome(
        val adding: Boolean = false,
        val nameError: String? = null,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val typed = MutableStateFlow(Typed())
    private val outcome = MutableStateFlow(Outcome())

    private val input = typed.map { Eip55.parse(it.address) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val detection = input.flatMapLatest { parsed ->
        when (parsed) {
            is AddressInput.Valid -> combine(
                observeAccount(parsed.address),
                observeAddressKind(parsed.address).map<KindReading, KindReading?> { it }.onStart { emit(null) },
            ) { lookup, reading -> detectionOf(parsed.address, lookup, reading) }
            else -> flowOf(null)
        }
    }

    val uiState: StateFlow<WatchUiState> = combine(typed, input, detection, outcome) { typed, parsed, card, outcome ->
        WatchUiState(
            address = typed.address,
            name = typed.name,
            addressError = addressError(typed.address, parsed),
            detection = card,
            adding = outcome.adding,
            nameError = outcome.nameError,
            error = outcome.error,
            done = outcome.done,
        )
    }.stateIn(
        viewModelScope,
        // The last state is kept (it holds what the user typed); on a return the card starts
        // again from "Checking", so no old verdict stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        WatchUiState(),
    )

    /** The address field changed; ignored while an add runs, whose result is about what was typed then. */
    fun onAddressChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { Typed(text, it.name) }
        outcome.value = Outcome()
    }

    /** The name field changed; ignored while an add runs. */
    fun onNameChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { Typed(it.address, text) }
        outcome.value = Outcome()
    }

    /** Adds the address, once; [WatchAddress] proves its kind again first. */
    fun add() {
        val address = (Eip55.parse(typed.value.address) as? AddressInput.Valid)?.address ?: return
        // outcome first: it is set synchronously below, while uiState follows it a step later,
        // so a second tap before that must still find the add running.
        if (outcome.value.adding || outcome.value.done || !uiState.value.canAdd) return
        outcome.value = Outcome(adding = true)
        viewModelScope.launch {
            outcome.value = when (val result = watchAddress(address, typed.value.name)) {
                is AddressWatch.Added -> Outcome(done = true)
                is AddressWatch.AlreadyInWallet ->
                    Outcome(error = "Already in your wallet as “${result.account.label}”.")
                AddressWatch.Contract -> Outcome(error = CONTRACT)
                is AddressWatch.NotVerified ->
                    Outcome(error = "The address cannot be checked right now. Nothing was added.")
                AddressWatch.NameTooLong ->
                    Outcome(nameError = "Use at most ${RenameAccount.MAX_LABEL_LENGTH} characters")
                AddressWatch.NotSaved -> Outcome(error = "The account could not be saved. Nothing was added.")
            }
        }
    }

    private fun detectionOf(address: Address, lookup: AccountLookup, reading: KindReading?): Detection = when {
        lookup is AccountLookup.Found -> Detection.InWallet(lookup.account.label)
        lookup is AccountLookup.ListUnavailable ->
            Detection.Unavailable("Your account list cannot be read right now, so nothing can be added.")
        reading == null -> Detection.Checking
        reading is KindReading.Syncing ->
            Detection.Unavailable("The light client is not synced yet. The address is checked as soon as it is.")
        reading is KindReading.Unavailable -> Detection.Unavailable(
            when (reading.reason) {
                BalanceReading.Reason.NO_ENGINE ->
                    "There is no light client on this device, so the address cannot be checked."
                BalanceReading.Reason.STALE_ANCHOR ->
                    "The light client refuses to sync, so the address cannot be checked."
                BalanceReading.Reason.UNPROVEN -> "No peer has proven this address's code yet. Serotine keeps trying."
            },
        )
        else -> {
            val verified = reading as KindReading.Verified
            when (val kind = verified.kind) {
                AddressKind.Eoa -> Detection.Account(Eip55.format(address), checkedAt(verified), delegate = null)
                is AddressKind.DelegatedEoa ->
                    Detection.Account(Eip55.format(address), checkedAt(verified), Eip55.format(kind.delegate))
                AddressKind.Contract -> Detection.Contract(CONTRACT)
            }
        }
    }

    private fun checkedAt(reading: KindReading.Verified) = "Checked at block ${grouped(reading.blockNumber)}"

    /** Why the typed text is no address, once that is clear: not while the user is still typing one. */
    private fun addressError(text: String, parsed: AddressInput): String? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() || parsed is AddressInput.Valid -> null
            parsed is AddressInput.ChecksumMismatch ->
                "The capitals do not match this address's checksum: check it for a typo."
            "." in trimmed -> "Enter the address itself; names (ENS) come with sending."
            trimmed.length >= ADDRESS_LENGTH || !HEX_SO_FAR.matches(trimmed) ->
                "Not an address: an address is 0x and 40 hexadecimal digits."
            else -> null
        }
    }

    private companion object {
        const val ADDRESS_LENGTH = 42
        val HEX_SO_FAR = Regex("^(0|0[xX][0-9a-fA-F]*)$")
        const val CONTRACT =
            "This address holds a contract. Serotine watches accounts now, and Safes once it can verify them."
    }
}
