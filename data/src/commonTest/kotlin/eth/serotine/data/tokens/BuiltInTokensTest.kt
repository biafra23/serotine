package eth.serotine.data.tokens

import eth.serotine.data.prices.ChainlinkFeeds
import eth.serotine.data.swaps.UniswapDeployments
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInTokensTest {

    @Test
    fun theSepoliaListHasOneEntryPerAddressAndSymbolEachWithAnIcon() {
        val tokens = BuiltInTokens.of(Network.SEPOLIA)

        assertEquals(tokens.size, tokens.distinctBy { it.address }.size)
        assertEquals(tokens.size, tokens.distinctBy { it.symbol }.size)
        assertTrue(tokens.all { it.icon != null }, "a built-in token has a bundled icon (CLAUDE.md, Data sources)")
        assertEquals(
            listOf("USDC", "EURC", "PYUSD", "LINK", "UNI", "WETH", "GHO", "DAI", "USDT", "WBTC", "AAVE"),
            tokens.map { it.symbol },
        )
    }

    @Test
    fun theEthereumListIsThePlansCandidatesEachWithAnIconAndAFeed() {
        val tokens = BuiltInTokens.of(Network.ETHEREUM)

        assertEquals(tokens.size, tokens.distinctBy { it.address }.size)
        assertTrue(tokens.all { it.icon != null }, "a built-in token has a bundled icon (CLAUDE.md, Data sources)")
        assertEquals(
            listOf("USDC", "USDT", "DAI", "WETH", "WBTC", "LINK", "UNI", "EURC", "PYUSD", "GHO", "AAVE"),
            tokens.map { it.symbol },
        )
        // Each priced on Ethereum by a feed pinned there, none by another network's, whose chain of
        // quotes ends in USD there (WBTC's goes through BTC).
        val quotes = ChainlinkFeeds.of(Network.ETHEREUM).associate { it.priceId to it.quote }
        tokens.forEach { token ->
            var id = token.priceId
            val seen = mutableSetOf<PriceId?>()
            while (id != null && id != PriceId.USD && seen.add(id)) id = quotes[id]
            assertEquals(PriceId.USD, id, "${token.symbol}'s feeds on Ethereum reach USD")
        }
    }

    @Test
    fun theBuiltInWethIsTheWeth9TheSwapsPin() {
        // ETH against this token is a wrap only while the two addresses are one (docs/swaps.md).
        for (network in listOf(Network.ETHEREUM, Network.SEPOLIA)) {
            val weth = BuiltInTokens.of(network).single { it.symbol == "WETH" }

            assertEquals(UniswapDeployments.of(network)?.deployment?.weth9, weth.address, network.displayName)
        }
    }
}
