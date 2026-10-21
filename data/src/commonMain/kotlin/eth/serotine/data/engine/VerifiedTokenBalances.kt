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
 * describes, and called again only at a head that [activity] cannot vouch for: one whose blocks
 * since the call it did not all check for a log naming the holder, as a transfer's does. (A
 * token that moves balances without a log naming the holder, such as a rebasing one, is called
 * again only when something else touches the holder.) A call that reverts, or returns anything but one word, is a verified "not a
 * token" ([TokenBalanceReading.NotAToken]); one the engine refuses for good is
 * [ReadUnavailable.REFUSED]; one it cannot answer right now is unproven. While no head can be
 * read, the last proof, made in this process or kept in [LastBalancesFile] from an earlier one,
 * is [TokenBalanceReading.LastKnown].
 */
class VerifiedTokenBalances internal constructor(
    chain: VerifiedChain?,
    dispatcher: CoroutineDispatcher,
    pollInterval: Duration,
    /** Where the last balances are kept across restarts; null keeps them for this process only. */
    lastBalances: LastBalancesFile? = null,
    /** What vouches for an unchanged balance between heads; none: a call at every head. */
    activity: AddressActivity? = null,
) : TokenBalances {

    /** One holder's balance of one token. */
    private data class Key(val holder: Address, val token: Address)

    /** A proven balance, or null for a verified answer that is no amount (and is not kept). */
    private val reads = ProvenReads<Key, TokenUnits?>(
        chain,
        dispatcher,
        pollInterval,
        kept = lastBalances?.kept<Key, TokenUnits?>(
            id = { "token:${it.holder.hex}:${it.token.hex}" },
            units = { it?.decimal },
            parse = TokenUnits::parse,
        ) ?: KeptProofs.none(),
        // A call whose block the engine did not name (UNKNOWN_BLOCK, negative) is never vouched for.
        unchangedSince = { key, provenAt, head ->
            activity?.unchangedSince(key.holder, AddressActivity.Kind.LOG, provenAt, head) == true
        },
    ) { verified, key ->
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
                    TokenBalanceReading.Verified(it, named(reading.blockNumber))
                } ?: TokenBalanceReading.NotAToken
                // Only an amount is offered as last known: an older "not a token" is not repeated.
                is EngineReading.LastKnown -> reading.value?.let {
                    TokenBalanceReading.LastKnown(it, named(reading.blockNumber), reading.asOf)
                } ?: TokenBalanceReading.Syncing
                is EngineReading.Unavailable -> TokenBalanceReading.Unavailable(reading.reason)
            }
        }

    /** [block], or null for [UNKNOWN_BLOCK]. */
    private fun named(block: Long): Long? = block.takeIf { it != UNKNOWN_BLOCK }

    private companion object {
        /** What the engine reports for a call whose block it did not name. */
        const val UNKNOWN_BLOCK = -1L
    }
}
