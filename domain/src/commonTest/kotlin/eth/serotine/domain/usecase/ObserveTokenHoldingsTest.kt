package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveTokenHoldingsTest {

    private val holder = Address.of("0x" + "ab".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, "link")
    private val tokens = MutableStateFlow(listOf(usdc, link))
    private val catalog = object : TokenCatalog {
        override fun observeTokens(): Flow<List<Token>> = tokens
    }
    private val asked = mutableListOf<Pair<Address, Address>>()
    private val balances = object : TokenBalances {
        override fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading> {
            asked += holder to token
            return flowOf(TokenBalanceReading.Verified(TokenUnits.of("5"), 9))
        }
    }

    @Test
    fun everyKnownTokenGetsARowStartingWithNoReadingYet() = runTest {
        val holdings = ObserveTokenHoldings(catalog, balances)(holder)

        assertEquals(listOf(TokenHolding(usdc, null), TokenHolding(link, null)), holdings.first())
        // combine may skip the in-between combinations: wait for where it settles.
        assertEquals(
            listOf(
                TokenHolding(usdc, TokenBalanceReading.Verified(TokenUnits.of("5"), 9)),
                TokenHolding(link, TokenBalanceReading.Verified(TokenUnits.of("5"), 9)),
            ),
            holdings.first { list -> list.all { it.reading != null } },
        )
        assertEquals(setOf(holder to usdc.address, holder to link.address), asked.toSet())
    }

    @Test
    fun noKnownTokensIsAnEmptyList() = runTest {
        tokens.value = emptyList()

        assertEquals(emptyList(), ObserveTokenHoldings(catalog, balances)(holder).first())
    }
}
