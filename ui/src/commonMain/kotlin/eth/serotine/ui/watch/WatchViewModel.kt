package eth.serotine.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.usecase.AddressWatch
import eth.serotine.domain.usecase.HoldNetwork
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.domain.usecase.control
import eth.serotine.domain.usecase.ownersHeld
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.RecipientState
import eth.serotine.ui.components.ResolvedAgain
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.recipientNote
import eth.serotine.ui.components.recipientStates
import eth.serotine.ui.components.recipientText
import eth.serotine.ui.components.resolveAgain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
 * the input) or an ENS name, resolved as Send's recipient is ([ResolveRecipient], against
 * finalized state), shows what the light client proves is at the address, and adds it through
 * [WatchAddress], which checks again before it adds. A name is resolved again at Add: what is
 * watched is the address it has then, and one that moved is said, not added (issue #71). Added
 * by a name and given none of the user's, the account's name follows that ENS name (issue #67).
 */
class WatchViewModel(
    observeAddressKind: ObserveAddressKind,
    observeAccounts: ObserveAccounts,
    private val watchAddress: WatchAddress,
    private val resolveRecipient: ResolveRecipient,
    /** The network this screen works on: the only one until the network steps (docs/networks.md, part 3). */
    private val network: Network,
    /** Keeps [network]'s light client running while this screen is open, with no account on it yet. */
    holdNetwork: HoldNetwork,
) : ViewModel() {

    /** Released when the screen is gone ([onCleared]). */
    private val hold = holdNetwork(network)

    override fun onCleared() {
        hold.release()
    }

    /** What is typed, and how often the user asked to resolve the name again ([lookupAttempt]). */
    private data class Typed(val address: String = "", val name: String = "", val lookupAttempt: Int = 0)

    private class Outcome(
        val adding: Boolean = false,
        val nameError: String? = null,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val typed = MutableStateFlow(Typed())
    private val outcome = MutableStateFlow(Outcome())

    /** The typed text resolved: an address at once, a name once the typing paused. */
    private val lookup: StateFlow<RecipientState> =
        recipientStates(typed.map { it.address.trim() to it.lookupAttempt }, ::lookUp).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            RecipientState.Empty,
        )

    /** The card of the address the lookup found, with that address: uiState shows it only for the lookup's own. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detection: Flow<Pair<Address, Detection>?> =
        lookup.map { ((it as? RecipientState.Done)?.result as? RecipientLookup.Found)?.address }
            .distinctUntilChanged()
            .flatMapLatest { address ->
                when (address) {
                    null -> flowOf(null)
                    // The one list says whether the address is in the wallet already, and, for a Safe,
                    // which of its owners' keys are held here.
                    else -> combine(
                        observeAccounts(),
                        observeAddressKind(
                            network,
                            address,
                        ).map<KindReading, KindReading?> { it }.onStart { emit(null) },
                    ) { list, reading -> address to detectionOf(address, list, reading) }
                }
            }

    val uiState: StateFlow<WatchUiState> = combine(typed, lookup, detection, outcome) { typed, lookup, card, outcome ->
        val text = typed.address.trim()
        // The lookup of what is typed now; one of an earlier text is no answer about it.
        val current = lookup.takeIf { it.text == text }
        val result = (current as? RecipientState.Done)?.result
        val found = result as? RecipientLookup.Found
        val (note, isError) = addressNote(text, current)
        WatchUiState(
            address = typed.address,
            name = typed.name,
            addressError = note.takeIf { isError },
            addressNote = note.takeUnless { isError },
            canRetryLookup = result is RecipientLookup.Unavailable,
            // The name an empty field leaves the account with: the ENS name, when it is one to follow.
            nameDefault = found?.name?.takeIf { it.length <= RenameAccount.MAX_LABEL_LENGTH },
            // Only the card of what is typed now: none while a new text is still being resolved, nor
            // one of the address before for a moment after the text changed.
            detection = card?.takeIf { (address, _) -> address == found?.address }?.second,
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
        typed.update { it.copy(address = text) }
        outcome.value = Outcome()
    }

    /** The name field changed; ignored while an add runs. */
    fun onNameChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { it.copy(name = text) }
        outcome.value = Outcome()
    }

    /** Resolves the typed name again, after a lookup that could not be verified. */
    fun onRetryLookup() {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
        outcome.value = Outcome()
    }

    /**
     * Adds the address, once: a name is resolved again first, and [WatchAddress] proves the
     * address's kind again.
     */
    fun add() {
        val found = foundInput() ?: return
        // outcome first: it is set synchronously below, while uiState follows it a step later,
        // so a second tap before that must still find the add running.
        if (outcome.value.adding || outcome.value.done || !uiState.value.canAdd) return
        outcome.value = Outcome(adding = true)
        viewModelScope.launch {
            val resolved = when (val again = resolveAgain(found, ::lookUp, ::nameChanged)) {
                is ResolvedAgain.Same -> again.found
                is ResolvedAgain.Changed -> {
                    // Resolved again for the card, which then shows what the name has now.
                    typed.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
                    outcome.value = Outcome(error = again.message)
                    return@launch
                }
            }
            outcome.value = when (val result = watchAddress(
                network,
                resolved.address,
                typed.value.name,
                resolved.name,
            )) {
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

    private fun detectionOf(address: Address, list: AccountList, reading: KindReading?): Detection {
        // In the wallet on this network: the same address on another network is another account.
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty().filter { it.network == network }
        return detectionOf(address, list, accounts.firstOrNull { it.address == address }, reading, accounts)
    }

    private fun detectionOf(
        address: Address,
        list: AccountList,
        inWallet: Account?,
        reading: KindReading?,
        accounts: List<Account>,
    ): Detection = when {
        inWallet != null -> Detection.InWallet(inWallet.label)
        list is AccountList.Unreadable ->
            Detection.Unavailable("Your account list cannot be read right now, so nothing can be added.")
        reading == null -> Detection.Checking
        reading is KindReading.Syncing ->
            Detection.Unavailable("The light client is not synced yet. The address is checked as soon as it is.")
        reading is KindReading.Unavailable -> Detection.Unavailable(
            when (reading.reason) {
                ReadUnavailable.NO_ENGINE ->
                    "There is no light client on this device, so the address cannot be checked."
                ReadUnavailable.NETWORK_OFF ->
                    "The network's light client is not running, so the address cannot be checked."
                ReadUnavailable.STALE_ANCHOR ->
                    "The light client refuses to sync, so the address cannot be checked."
                // Not the code's fault alone: for a Safe, its code may be proven and its storage
                // (owners, threshold) not, and the reading does not tell the two apart.
                ReadUnavailable.UNPROVEN -> "What is at this address could not be verified this time: its code, or " +
                    "for a Safe its owners and threshold. Serotine keeps trying."
                ReadUnavailable.REFUSED -> "The light client on this device cannot check this address."
            },
        )
        else -> {
            val verified = reading as KindReading.Verified
            when (val kind = verified.kind) {
                AddressKind.Eoa -> Detection.Account(Eip55.format(address), checkedAt(verified), delegate = null)
                is AddressKind.DelegatedEoa ->
                    Detection.Account(Eip55.format(address), checkedAt(verified), Eip55.format(kind.delegate))
                is AddressKind.Safe -> safeCard(address, kind.state, accounts, verified)
                AddressKind.Contract -> Detection.Contract(CONTRACT)
            }
        }
    }

    private fun checkedAt(reading: KindReading.Verified) = "Checked at block ${grouped(reading.blockNumber)}"

    private fun safeCard(address: Address, state: SafeState, accounts: List<Account>, verified: KindReading.Verified) =
        Detection.Safe(
            address = Eip55.format(address),
            title = "Safe ${state.version}" + if (state.l2) " (L2)" else "",
            signers = "${state.threshold} of ${state.owners.size} owners",
            owners = state.owners.map(Eip55::format),
            modules = state.modules.map(Eip55::format),
            guard = state.guard?.let(Eip55::format),
            checkedAt = checkedAt(verified),
            control = Control.of(state.control(state.ownersHeld(accounts).size)),
            moduleGuard = state.moduleGuard?.let(Eip55::format),
            threshold = "${state.threshold}/${state.owners.size}",
        )

    /** The address typed, once the lookup found one for the text as it is now. */
    private fun foundInput(): RecipientLookup.Found? {
        val lookup = lookup.value as? RecipientState.Done ?: return null
        if (lookup.text != typed.value.address.trim()) return null
        return lookup.result as? RecipientLookup.Found
    }

    /**
     * What the address field says under [text] ([lookup] its lookup, null while it is of an
     * earlier text), and whether that is an error: as Send's recipient field, but for the zero
     * address, which nobody can hold, and with the block a name was resolved at.
     */
    private fun addressNote(text: String, lookup: RecipientState?): Pair<String?, Boolean> {
        val result = (lookup as? RecipientState.Done)?.result
        val found = result as? RecipientLookup.Found
        // A block only for a name: an address typed is no resolution.
        val resolvedAt = found?.blockNumber
        return when {
            result == RecipientLookup.ZeroAddress -> ZERO_ADDRESS to true
            // In full, whatever the card can say of it: the address the name points to is what is added.
            found != null && resolvedAt != null ->
                "Resolves to ${Eip55.format(found.address)}, verified at finalized block ${grouped(resolvedAt)}" to
                    false
            else -> recipientNote(text, lookup)
        }
    }

    private suspend fun lookUp(text: String): RecipientLookup = try {
        resolveRecipient(network, recipientText(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    /** Why [name]'s address is not added: it could not be verified again ([unverifiable]), or it moved. */
    private fun nameChanged(name: String, unverifiable: Boolean): String = if (unverifiable) {
        "“$name” could not be resolved with verified calls again just now. Nothing was added."
    } else {
        "“$name” no longer resolves to the address that was shown: check the address it has now. " +
            "Nothing was added."
    }

    private companion object {
        const val ZERO_ADDRESS = "The zero address: nobody holds its key, so there is no account to watch."
        const val CONTRACT =
            "This address holds a contract that is not a recognised Safe. Serotine watches accounts and Safes."
    }
}
