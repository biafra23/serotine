package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * [TokenMetadata] over a verified light client: a contract's `symbol()`, `name()` and
 * `decimals()` as verified calls ([VerifiedChain.call]), polled through [ProvenReads] until
 * they are answered, then kept for the life of the process — a token's description is read
 * once, not at every head. `symbol()` and `decimals()` must answer; `name()` may be missing
 * (some tokens have none) and is then the symbol. A revert, an answer that is no text or no
 * small number, or decimals no amount can be shown at, is a verified "not a token".
 */
class VerifiedTokenMetadata internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
) : TokenMetadata {

    private val reads = ProvenReads<Address, MetadataReading>(chain, dispatcher, pollInterval) { verified, token ->
        val decimals = verified.call(token, Abi.DECIMALS) ?: return@ProvenReads null
        val symbol = verified.call(token, Abi.SYMBOL) ?: return@ProvenReads null
        val name = verified.call(token, Abi.NAME) ?: return@ProvenReads null
        if (listOf(decimals, symbol, name).any { it == CallOutcome.Refused }) {
            return@ProvenReads EngineReading.Unavailable(ReadUnavailable.REFUSED)
        }
        val block = (decimals as? CallOutcome.Returned)?.blockNumber
        EngineReading.Proven(described(decimals, symbol, name, block), block ?: UNKNOWN_BLOCK)
    }

    /** The final answers, per token, for the life of the process. */
    private val answered = mutableMapOf<Address, MetadataReading>()
    private val answeredLock = Mutex()

    override fun observe(token: Address): Flow<MetadataReading> = flow {
        answeredLock.withLock { answered[token] }?.let {
            emit(it)
            return@flow
        }
        val readings = reads.observe(token)
            .map { reading ->
                when (reading) {
                    EngineReading.Syncing -> MetadataReading.Syncing
                    is EngineReading.Proven -> reading.value
                    is EngineReading.Unavailable -> MetadataReading.Unavailable(reading.reason)
                }
            }
            .onEach { if (it.isFinal()) answeredLock.withLock { answered[token] = it } }
            .transformWhile { reading ->
                emit(reading)
                !reading.isFinal()
            }
        emitAll(readings)
    }

    private fun MetadataReading.isFinal() =
        this is MetadataReading.Verified || this == MetadataReading.NotAToken ||
            this == MetadataReading.Unavailable(ReadUnavailable.REFUSED)

    private fun described(
        decimals: CallOutcome,
        symbol: CallOutcome,
        name: CallOutcome,
        block: Long?,
    ): MetadataReading {
        val places = (decimals as? CallOutcome.Returned)?.let { Abi.uint256(it.hex) }?.toIntOrNull()
            ?.takeIf { it in 0..Token.MAX_DECIMALS } ?: return MetadataReading.NotAToken
        val said = (symbol as? CallOutcome.Returned)?.let { Abi.text(it.hex) }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return MetadataReading.NotAToken
        val called = (name as? CallOutcome.Returned)?.let { Abi.text(it.hex) }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: said
        return MetadataReading.Verified(said, called, places, block)
    }

    private companion object {
        /** What [ProvenReads] is given for a call whose block the engine did not name. */
        const val UNKNOWN_BLOCK = -1L
    }
}
