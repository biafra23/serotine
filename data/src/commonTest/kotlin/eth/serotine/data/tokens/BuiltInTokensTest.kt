package eth.serotine.data.tokens

import eth.serotine.domain.model.Network
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
}
