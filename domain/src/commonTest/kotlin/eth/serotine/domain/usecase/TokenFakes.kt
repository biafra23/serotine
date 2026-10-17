package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenChange
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** [UserTokens] held in memory; [unreadable] and [failSaves] make it fail. */
internal class MemoryUserTokens : UserTokens {
    val settings = MutableStateFlow<TokenSettings>(TokenSettings.Loaded(emptyList(), emptySet()))
    var failSaves = false

    private val loaded get() = settings.value as? TokenSettings.Loaded

    override fun observe(): Flow<TokenSettings> = settings

    override suspend fun add(token: Token): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.any { it.address == token.address }) return TokenChange.AlreadyPresent
        settings.value = current.copy(added = current.added + token)
        return TokenChange.Changed
    }

    override suspend fun remove(address: Address): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if (current.added.none { it.address == address }) return TokenChange.NotFound
        settings.value =
            TokenSettings.Loaded(current.added.filterNot { it.address == address }, current.hidden - address)
        return TokenChange.Changed
    }

    override suspend fun setHidden(address: Address, hidden: Boolean): TokenChange {
        val current = loaded ?: return TokenChange.NotSaved
        if (failSaves) return TokenChange.NotSaved
        if ((address in current.hidden) == hidden) return TokenChange.AlreadyPresent
        settings.value = current.copy(hidden = if (hidden) current.hidden + address else current.hidden - address)
        return TokenChange.Changed
    }
}

/** [TokenMetadata] whose reading per token a test sets; [MetadataReading.Syncing] until then. */
internal class FakeMetadata : TokenMetadata {
    private val readings = mutableMapOf<Address, MutableStateFlow<MetadataReading>>()

    fun of(token: Address) = readings.getOrPut(token) { MutableStateFlow(MetadataReading.Syncing) }

    override fun observe(token: Address): Flow<MetadataReading> = of(token)
}

/** A [TokenCatalog] of the built-in [tokens]. */
internal class ListCatalog(private vararg val tokens: Token) : TokenCatalog {
    override fun observeTokens(): Flow<List<Token>> = flowOf(tokens.toList())
}
