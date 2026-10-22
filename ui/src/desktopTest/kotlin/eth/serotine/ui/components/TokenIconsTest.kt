package eth.serotine.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenIconsTest {

    /**
     * The icon keys of `BuiltInTokens` (`:data`, which `:ui` cannot see) and of the native
     * currency: a key with no icon would fall back to a monogram without failing anything else.
     */
    private val keys =
        listOf("eth", "usdc", "eurc", "pyusd", "link", "uni", "weth", "gho", "dai", "usdt", "wbtc", "aave")

    @Test
    fun everyBuiltInKeyHasAnIconDrawnAt32Dp() {
        keys.forEach { key ->
            val icon = assertNotNull(TokenIcons.of(key), key)
            assertEquals(32f, icon.defaultWidth.value, key)
            assertEquals(icon.viewportWidth, icon.viewportHeight, key)
        }
    }

    @Test
    fun anUnknownKeyHasNone() {
        assertNull(TokenIcons.of("eurs"))
    }
}
