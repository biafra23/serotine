package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What an account holds of each token the wallet shows, as a stream the account screen
 * renders: one [TokenHolding] per shown token ([KnownToken.shown]: not hidden, not a built-in
 * entry its contract contradicts), in the known tokens' order, each with its verified reading
 * — or none yet ([TokenHolding.reading] null) until the first one. Which of them to show (the
 * held ones, say) is the screen's decision: only known tokens can be shown at all, since
 * finding the others takes an indexer (docs/wallet-ux.md, *Tokens*).
 *
 * Each token's balance is read on its own: a token hidden or added starts or stops its own
 * reading only, so the others keep theirs, and none waits for a read in flight to finish.
 */
class ObserveTokenHoldings(
    private val knownTokens: ObserveKnownTokens,
    private val balances: TokenBalances,
) {
    operator fun invoke(holder: Address): Flow<TokenHoldings> = channelFlow {
        val readings = MutableStateFlow<Map<Address, TokenBalanceReading>>(emptyMap())
        // Touched only where the shown tokens are collected, one change at a time.
        val readers = mutableMapOf<Address, Job>()
        val shown = knownTokens()
            .map { known -> known.tokens.filter { it.shown } to known.settingsUnavailable }
            .onEach { (tokens, _) ->
                val wanted = tokens.mapTo(HashSet()) { it.token.address }
                readers.keys.filterNot { it in wanted }.forEach { address ->
                    // Not joined: a read in flight ends on its own, and its answer is dropped.
                    readers.remove(address)?.cancel()
                    readings.update { it - address }
                }
                tokens.map { it.token.address }.filterNot { it in readers }.forEach { address ->
                    readers[address] = launch {
                        balances.observeBalance(holder, address).collect { reading ->
                            readings.update { it + (address to reading) }
                        }
                    }
                }
            }
        combine(shown, readings) { (tokens, settingsUnavailable), read ->
            TokenHoldings(
                tokens.map { TokenHolding(it.token, read[it.token.address], it.sharesBuiltInSymbol) },
                settingsUnavailable,
            )
        }.collect { send(it) }
    }
}

/**
 * What [ObserveTokenHoldings] emits: the [holdings], and whether the user's token settings
 * cannot be read ([settingsUnavailable]): the tokens they hid are then shown, and the ones they
 * added are missing.
 */
data class TokenHoldings(val holdings: List<TokenHolding>, val settingsUnavailable: Boolean = false)

/**
 * What an account holds of [token]: its verified [reading], or null before the first one;
 * [sharesBuiltInSymbol] for a token the user added whose symbol is a built-in token's, which
 * it is not.
 */
data class TokenHolding(
    val token: Token,
    val reading: TokenBalanceReading?,
    val sharesBuiltInSymbol: Boolean = false,
)
