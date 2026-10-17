package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenUnits
import kotlinx.coroutines.flow.Flow

/**
 * The port for ERC-20 balances, as the embedded light client proves them: the token
 * contract's own `balanceOf`, run as a verified call. :data implements it over the Myotis
 * engine (`eth.serotine.data.engine`), on the one network the app works on; nothing else may
 * answer it — no indexer, no token API (CLAUDE.md, *Trust*).
 */
interface TokenBalances {
    /**
     * The balance [holder] has of the token at [token], re-emitted when the verified head
     * moves or the light client's state changes; consecutive equal readings are not
     * repeated. Never fails.
     */
    fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading>
}

/** One answer of [TokenBalances.observeBalance]. */
sealed interface TokenBalanceReading {

    /** The light client has no verified head yet. */
    data object Syncing : TokenBalanceReading

    /**
     * [units] of the token, from a verified call against the state of block [blockNumber]
     * (null when the engine did not say which block it ran against).
     */
    data class Verified(val units: TokenUnits, val blockNumber: Long?) : TokenBalanceReading

    /**
     * The contract did not answer `balanceOf` as a token does: the verified call reverted or
     * returned something that is no amount. A verified answer, not a passing failure.
     */
    data object NotAToken : TokenBalanceReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : TokenBalanceReading
}
