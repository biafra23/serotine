package eth.serotine.ui.addtoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.domain.usecase.TokenAdd
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Add token's ViewModel: reads the typed text as an address (EIP-55 checked at the input),
 * shows what the contract there says about itself, verified, and adds it through [AddToken],
 * which reads it again before it adds.
 */
class AddTokenViewModel(
    observeKnownTokens: ObserveKnownTokens,
    observeTokenMetadata: ObserveTokenMetadata,
    private val addToken: AddToken,
) : ViewModel() {

    private class Outcome(val adding: Boolean = false, val error: String? = null, val done: Boolean = false)

    private val typed = MutableStateFlow("")
    private val outcome = MutableStateFlow(Outcome())
    private val input = typed.map { Eip55.parse(it) }.distinctUntilChanged()

    /**
     * The card of the address last typed, with that address. A new address does not wait for
     * the last one's read to end (a verified call can take long): that read is cancelled and
     * left to finish on its own, and a card it might still produce names its own address,
     * which is then not the one typed.
     */
    private val card: Flow<Pair<Address, TokenCard>?> = channelFlow {
        var current: Job? = null
        input.collect { parsed ->
            current?.cancel()
            if (parsed is AddressInput.Valid) {
                current = launch {
                    combine(
                        observeKnownTokens(),
                        observeTokenMetadata(parsed.address)
                            .map<MetadataReading, MetadataReading?> { it }
                            .onStart { emit(null) },
                    ) { known, reading -> parsed.address to cardOf(parsed.address, known, reading) }
                        .collect { send(it) }
                }
            } else {
                current = null
                send(null)
            }
        }
    }

    val uiState: StateFlow<AddTokenUiState> = combine(typed, input, card, outcome) { text, parsed, card, outcome ->
        val typedAddress = (parsed as? AddressInput.Valid)?.address
        AddTokenUiState(
            address = text,
            addressError = addressError(text, parsed),
            // Only the typed address's card: until it has one, it is being read.
            card = card?.takeIf { (address, _) -> address == typedAddress }?.second
                ?: TokenCard.Checking.takeIf { typedAddress != null },
            adding = outcome.adding,
            error = outcome.error,
            done = outcome.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddTokenUiState())

    /** The address field changed; ignored while an add runs. */
    fun onAddressChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.value = text
        outcome.value = Outcome()
    }

    /**
     * Adds the token, once, and only the one whose card is shown: the card must be the typed
     * address's. [AddToken] reads the contract again first.
     */
    fun add() {
        val address = (Eip55.parse(typed.value) as? AddressInput.Valid)?.address ?: return
        val shown = uiState.value.card as? TokenCard.Claim ?: return
        if (Address.parse(shown.address) != address) return
        if (outcome.value.adding || outcome.value.done || !uiState.value.canAdd) return
        outcome.value = Outcome(adding = true)
        viewModelScope.launch {
            outcome.value = when (val result = addToken(address)) {
                is TokenAdd.Added -> Outcome(done = true)
                is TokenAdd.AlreadyKnown ->
                    Outcome(error = "The wallet knows this token already, as ${result.token.symbol}.")
                TokenAdd.NotAToken -> Outcome(error = NOT_A_TOKEN)
                is TokenAdd.NotVerified -> Outcome(error = "The contract cannot be read right now. Nothing was added.")
                TokenAdd.NotSaved -> Outcome(error = "The token could not be saved. Nothing was added.")
            }
        }
    }

    private fun cardOf(address: Address, known: KnownTokenList, reading: MetadataReading?): TokenCard {
        known.tokens.firstOrNull { it.token.address == address }?.let { return TokenCard.Known(it.token.symbol) }
        return when (reading) {
            null, MetadataReading.Syncing -> if (reading == null) TokenCard.Checking else TokenCard.Unavailable(SYNCING)
            MetadataReading.NotAToken -> TokenCard.Unavailable(NOT_A_TOKEN)
            is MetadataReading.Unavailable -> TokenCard.Unavailable(
                when (reading.reason) {
                    ReadUnavailable.NO_ENGINE ->
                        "There is no light client on this device, so the contract cannot be read."
                    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the contract cannot be read."
                    ReadUnavailable.UNPROVEN -> "No peer has proven the contract's answers yet. Serotine keeps trying."
                    ReadUnavailable.REFUSED -> "The light client on this device cannot read this contract."
                },
            )
            is MetadataReading.Verified -> TokenCard.Claim(
                address = Eip55.format(address),
                symbol = reading.symbol,
                name = reading.name,
                decimals = reading.decimals,
                checkedAt = reading.blockNumber?.let { "Read from the contract at block ${grouped(it)}" }
                    ?: "Read from the contract",
                warning = known.tokens.firstOrNull { it.token.symbol.equals(reading.symbol, ignoreCase = true) }
                    ?.let { "Not the ${it.token.symbol} the wallet lists: another contract calls itself that" },
            )
        }
    }

    private fun addressError(text: String, parsed: AddressInput): String? = when {
        text.isBlank() || parsed is AddressInput.Valid -> null
        parsed is AddressInput.ChecksumMismatch ->
            "The capitals do not match this address's checksum: check it for a typo."
        text.trim().length >= ADDRESS_LENGTH -> "Not an address: an address is 0x and 40 hexadecimal digits."
        else -> null
    }

    private companion object {
        const val ADDRESS_LENGTH = 42
        const val NOT_A_TOKEN = "This address does not answer as a token."
        const val SYNCING = "The light client is not synced yet. The contract is read as soon as it is."
    }
}
