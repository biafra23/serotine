package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What an account holds of each token the wallet shows, as a stream the account screen
 * renders: one [TokenHolding] per shown token ([KnownTokenList.shown]: not hidden, not a
 * built-in entry its contract contradicts), in the known tokens' order, each with its verified
 * reading — or none yet ([TokenHolding.reading] null) until the first one. Which of them to
 * show (the held ones, say) is the screen's decision: only known tokens can be shown at all,
 * since finding the others takes an indexer (docs/wallet-ux.md, *Tokens*).
 */
class ObserveTokenHoldings(
    private val knownTokens: ObserveKnownTokens,
    private val balances: TokenBalances,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(holder: Address): Flow<List<TokenHolding>> =
        // Only a change of the shown tokens restarts the balances: a check that agrees changes nothing.
        knownTokens().map { it.shown }.distinctUntilChanged().flatMapLatest { tokens -> holdings(holder, tokens) }

    private fun holdings(holder: Address, tokens: List<Token>): Flow<List<TokenHolding>> =
        if (tokens.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                tokens.map { token ->
                    balances.observeBalance(holder, token.address)
                        .map<TokenBalanceReading, TokenBalanceReading?> { it }
                        .onStart { emit(null) }
                        .map { TokenHolding(token, it) }
                },
            ) { it.toList() }
        }
}

/** What an account holds of [token]: its verified [reading], or null before the first one. */
data class TokenHolding(val token: Token, val reading: TokenBalanceReading?)
