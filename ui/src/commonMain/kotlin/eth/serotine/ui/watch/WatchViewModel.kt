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
import eth.serotine.domain.usecase.NetworkHold
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.domain.usecase.control
import eth.serotine.domain.usecase.ownersHeld
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkLabel
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
 *
 * The address is checked on every network that is on ([ObserveEnabledNetworks]), each held
 * running while the screen is open ([HoldNetwork]): with one, as one card; with more, a row each,
 * and an address with no code on all of them offered on all of them, or on those ticked
 * (docs/networks.md, N10 and N11). A name is resolved on the first network that is on.
 */
class WatchViewModel(
    observeAddressKind: ObserveAddressKind,
    observeAccounts: ObserveAccounts,
    private val watchAddress: WatchAddress,
    private val resolveRecipient: ResolveRecipient,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    /** Keeps each network that is on running while this screen is open, with no account on it yet. */
    private val holdNetwork: HoldNetwork,
) : ViewModel() {

    /** The networks on in this wallet, in `Network` order; null until read, so none is said to be off before. */
    private val on: StateFlow<List<Network>?> =
        observeEnabledNetworks().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The network whose light client the strip shows: the first that is on (the one a name is resolved on). */
    val stripNetwork: StateFlow<Network> = on.map { it?.firstOrNull() ?: Network.entries.first() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Network.entries.first())

    /** A hold on each network that is on, released when it goes off or the screen is gone ([onCleared]). */
    private val holds = mutableMapOf<Network, NetworkHold>()

    init {
        viewModelScope.launch {
            on.collect { read ->
                val networks = read ?: return@collect
                (holds.keys - networks.toSet()).forEach { holds.remove(it)?.release() }
                networks.filter { it !in holds }.forEach { holds[it] = holdNetwork(it) }
            }
        }
    }

    override fun onCleared() {
        holds.values.forEach { it.release() }
        holds.clear()
    }

    /** What is typed, and how often the user asked to resolve the name again ([lookupAttempt]). */
    private data class Typed(val address: String = "", val name: String = "", val lookupAttempt: Int = 0)

    private class Outcome(
        val adding: Boolean = false,
        val nameError: String? = null,
        val error: String? = null,
        val done: Boolean = false,
    )

    /** The networks ticked (null: those offered, until the user ticks one), and whether they are being chosen. */
    private data class Choice(val chosen: Set<Network>? = null, val choosing: Boolean = false)

    private val choice = MutableStateFlow(Choice())

    private val typed = MutableStateFlow(Typed())
    private val outcome = MutableStateFlow(Outcome())

    /** The typed text resolved: an address at once, a name once the typing paused. */
    private val lookup: StateFlow<RecipientState> =
        recipientStates(typed.map { it.address.trim() to it.lookupAttempt }, ::lookUp).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            RecipientState.Empty,
        )

    /**
     * The cards of the address the lookup found on each network that is on, with that address:
     * uiState shows them only for the lookup's own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detection: Flow<Pair<Address, List<Pair<Network, Detection>>>?> =
        combine(
            lookup.map { ((it as? RecipientState.Done)?.result as? RecipientLookup.Found)?.address }
                .distinctUntilChanged(),
            on,
            ::Pair,
        )
            .distinctUntilChanged()
            .flatMapLatest { (address, read) ->
                val networks = read.orEmpty()
                when {
                    address == null || read == null -> flowOf(null)
                    networks.isEmpty() -> flowOf(address to emptyList())
                    // The one list says whether the address is in the wallet already, and, for a Safe,
                    // which of its owners' keys are held here.
                    else -> combine(
                        observeAccounts(),
                        combine(
                            networks.map { network ->
                                observeAddressKind(network, address)
                                    .map<KindReading, KindReading?> { it }
                                    .onStart { emit(null) }
                            },
                        ) { it.toList() },
                    ) { list, readings ->
                        address to networks.zip(readings) { network, reading ->
                            network to detectionOf(network, address, list, reading)
                        }
                    }
                }
            }

    val uiState: StateFlow<WatchUiState> = combine(
        combine(typed, choice, ::Pair),
        lookup,
        detection,
        outcome,
        on,
    ) { (typed, choice), lookup, cards, outcome, on ->
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
            adding = outcome.adding,
            nameError = outcome.nameError,
            error = outcome.error,
            done = outcome.done,
            noNetwork = NO_NETWORK.takeIf { on?.isEmpty() == true },
            offNetworks = on?.let { read -> Network.entries.filter { it !in read }.map(NetworkLabel::of) }.orEmpty(),
        ).withCards(
            // Only the cards of what is typed now: none while a new text is still being resolved,
            // nor those of the address before for a moment after the text changed.
            cards?.takeIf { (address, _) -> address == found?.address }?.second,
            choice,
        )
    }.stateIn(
        viewModelScope,
        // The last state is kept (it holds what the user typed); on a return the card starts
        // again from "Checking", so no old verdict stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        WatchUiState(),
    )

    /**
     * The cards of each network ([cards]): the one network's as [WatchUiState.detection], or, with
     * more, a row each, with the all-networks question for an address with no code on every one.
     */
    private fun WatchUiState.withCards(cards: List<Pair<Network, Detection>>?, choice: Choice): WatchUiState {
        if (cards == null) return this
        if (cards.size == 1) return copy(detection = cards.single().second)
        // A Safe exists only where it was deployed: where it is one, it is offered there alone (N11).
        val safeSomewhere = cards.any { (_, card) -> card is Detection.Safe }
        val offered = cards.filter { (_, card) -> if (safeSomewhere) card is Detection.Safe else card.addable }
            .map { it.first }
            .toSet()
        val chosen = choice.chosen ?: offered
        val everyAccount = cards.isNotEmpty() && cards.all { (_, card) -> card is Detection.Account }
        return copy(
            networks = cards.map { (network, card) ->
                WatchNetwork(
                    NetworkLabel.of(network),
                    card,
                    offered = network in offered,
                    chosen = network in chosen && network in offered,
                )
            },
            question = if (everyAccount) {
                "This address has no code. Watch it on ${networkNames(cards.map { it.first })}?"
            } else {
                null
            },
            choosing = choice.choosing,
        )
    }

    /** "Ethereum and Gnosis Chain", or "all three networks". */
    private fun networkNames(networks: List<Network>): String = when (networks.size) {
        3 -> "all three networks"
        else -> networks.map { it.displayName }.let { names ->
            if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
        }
    }

    /** Turns the network of [chainId] on: the address is then checked there too. */
    fun onTurnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { setNetworkEnabled(network, on = true) }
    }

    /** Shows the networks as check boxes instead of the all-networks question (N10). */
    fun onChooseNetworks() {
        if (outcome.value.adding || outcome.value.done) return
        choice.update { it.copy(choosing = true) }
    }

    /** Ticks the network of [chainId], or unticks it. */
    fun onNetworkToggle(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if (outcome.value.adding || outcome.value.done) return
        val offered = uiState.value.networks.filter { it.offered }
            .mapNotNull { Network.ofChainId(it.network.chainId) }
        choice.update { current ->
            val chosen = current.chosen ?: offered.toSet()
            current.copy(chosen = if (network in chosen) chosen - network else chosen + network)
        }
    }

    /** The address field changed; ignored while an add runs, whose result is about what was typed then. */
    fun onAddressChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.update { it.copy(address = text) }
        choice.value = Choice()
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
        val state = uiState.value
        if (outcome.value.adding || outcome.value.done || !state.canAdd) return
        // The networks to add it on: the one on; or all offered, as the question asks; or those ticked.
        val networks = when {
            state.networks.isEmpty() -> on.value.orEmpty().take(1)
            state.question != null && !state.choosing -> state.networks.filter { it.offered }
                .mapNotNull { Network.ofChainId(it.network.chainId) }
            else -> state.networks.filter { it.offered && it.chosen }
                .mapNotNull { Network.ofChainId(it.network.chainId) }
        }
        if (networks.isEmpty()) return
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
            outcome.value = watchOn(networks, resolved)
        }
    }

    /**
     * Watches [resolved] on each of [networks], stopping at the first that cannot be added, whose
     * reason is said, with the networks it was added on before it.
     */
    private suspend fun watchOn(networks: List<Network>, resolved: RecipientLookup.Found): Outcome {
        val added = mutableListOf<Network>()
        for (network in networks) {
            val result = watchAddress(network, resolved.address, typed.value.name, resolved.name)
            if (result is AddressWatch.Added) {
                added += network
                continue
            }
            if (result == AddressWatch.NameTooLong) {
                return Outcome(nameError = "Use at most ${RenameAccount.MAX_LABEL_LENGTH} characters")
            }
            val why = when (result) {
                is AddressWatch.AlreadyInWallet -> "Already in your wallet as “${result.account.label}”."
                AddressWatch.Contract -> CONTRACT
                is AddressWatch.NotVerified -> "The address cannot be checked right now."
                else -> "The account could not be saved."
            }
            return Outcome(
                error = if (added.isEmpty()) {
                    // In the wallet already says itself that nothing changed.
                    if (result is AddressWatch.AlreadyInWallet) why else "$why Nothing was added."
                } else {
                    "Added on ${added.joinToString { it.displayName }}, but not on ${network.displayName}: $why"
                },
            )
        }
        return Outcome(done = true)
    }

    private fun detectionOf(
        network: Network,
        address: Address,
        list: AccountList,
        reading: KindReading?,
    ): Detection {
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
        // A name on the first network that is on; with none on, nothing can be resolved.
        val network = on.value?.firstOrNull() ?: return RecipientLookup.Unavailable(ReadUnavailable.NETWORK_OFF)
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
        const val NO_NETWORK = "No network is on in this wallet, so the address cannot be checked. Turn one on in " +
            "Settings → Networks."
        const val CONTRACT =
            "This address holds a contract that is not a recognised Safe. Serotine watches accounts and Safes."
    }
}
