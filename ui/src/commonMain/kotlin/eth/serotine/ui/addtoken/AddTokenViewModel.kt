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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val card = input.flatMapLatest { parsed ->
        when (parsed) {
            is AddressInput.Valid -> combine(
                observeKnownTokens(),
                observeTokenMetadata(parsed.address)
                    .map<MetadataReading, MetadataReading?> { it }
                    .onStart { emit(null) },
            ) { known, reading -> cardOf(parsed.address, known, reading) }
            else -> flowOf(null)
        }
    }

    val uiState: StateFlow<AddTokenUiState> = combine(typed, input, card, outcome) { text, parsed, card, outcome ->
        AddTokenUiState(
            address = text,
            addressError = addressError(text, parsed),
            card = card,
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

    /** Adds the token, once; [AddToken] reads the contract again first. */
    fun add() {
        val address = (Eip55.parse(typed.value) as? AddressInput.Valid)?.address ?: return
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
                if (reading.reason == ReadUnavailable.REFUSED) REFUSED else UNPROVEN,
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
        const val UNPROVEN = "No peer has proven the contract's answers yet. Serotine keeps trying."
        const val REFUSED = "The light client on this device cannot read this contract."
    }
}
