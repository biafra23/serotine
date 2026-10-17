package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration

/**
 * [TokenBalances] over a verified light client: the token contract's `balanceOf(holder)` run
 * as a verified call ([VerifiedChain.call]), polled, cached and refused as [ProvenReads]
 * describes. A call that reverts, or returns anything but one word, is a verified "not a
 * token" ([TokenBalanceReading.NotAToken]); one the engine refuses for good is
 * [ReadUnavailable.REFUSED]; one it cannot answer right now is unproven.
 */
class VerifiedTokenBalances internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
) : TokenBalances {

    /** One holder's balance of one token. */
    private data class Key(val holder: Address, val token: Address)

    /** A proven balance, or null for a verified answer that is no amount. */
    private val reads = ProvenReads<Key, TokenUnits?>(chain, dispatcher, pollInterval) { verified, key ->
        when (val outcome = verified.call(key.token, Abi.balanceOf(key.holder))) {
            is CallOutcome.Returned -> EngineReading.Proven(
                Abi.uint256(outcome.hex)?.let(TokenUnits::parse),
                outcome.blockNumber ?: UNKNOWN_BLOCK,
            )
            is CallOutcome.Reverted -> EngineReading.Proven(null, outcome.blockNumber ?: UNKNOWN_BLOCK)
            CallOutcome.Refused -> EngineReading.Unavailable(ReadUnavailable.REFUSED)
            null -> null
        }
    }

    override fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading> =
        reads.observe(Key(holder, token)).map { reading ->
            when (reading) {
                EngineReading.Syncing -> TokenBalanceReading.Syncing
                is EngineReading.Proven -> reading.value?.let {
                    TokenBalanceReading.Verified(it, reading.blockNumber.takeIf { block -> block != UNKNOWN_BLOCK })
                } ?: TokenBalanceReading.NotAToken
                is EngineReading.Unavailable -> TokenBalanceReading.Unavailable(reading.reason)
            }
        }

    private companion object {
        /** What the engine reports for a call whose block it did not name. */
        const val UNKNOWN_BLOCK = -1L
    }
}
