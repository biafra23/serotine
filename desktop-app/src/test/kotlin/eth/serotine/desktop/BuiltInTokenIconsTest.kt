package eth.serotine.desktop

import eth.serotine.data.tokens.BuiltInTokens
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.TokenIcons
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The built-in list (`:data`) against the bundled icons (`:ui`), which only a host sees both
 * of: a key without an icon would draw a monogram and fail nothing else.
 */
class BuiltInTokenIconsTest {

    @Test
    fun everyBuiltInTokenAndNativeCurrencyHasABundledIcon() {
        Network.entries.forEach { network ->
            val keys = BuiltInTokens.of(network).map { it.icon } + network.nativeIcon
            val missing = keys.filter { key -> key == null || TokenIcons.of(key) == null }
            assertTrue(missing.isEmpty(), "$network: no bundled icon for $missing")
        }
    }
}
