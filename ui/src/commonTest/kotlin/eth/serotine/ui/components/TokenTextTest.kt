package eth.serotine.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenTextTest {

    @Test
    fun aShortSymbolOrNameIsShownAsIs() {
        assertEquals("USDC", displaySymbol("USDC"))
        assertEquals("ChainLink Token", displayName("ChainLink Token"))
    }

    @Test
    fun whatAContractMakesTooLongIsCutAndSaysSo() {
        assertEquals("A".repeat(SYMBOL_CHARS) + "…", displaySymbol("A".repeat(1_000)))
        assertEquals("B".repeat(NAME_CHARS) + "…", displayName("B".repeat(NAME_CHARS + 1)))
    }

    @Test
    fun aCutNeverSplitsACharacterBeyondTheBmp() {
        val rocket = "🚀"
        assertEquals("A".repeat(SYMBOL_CHARS - 1) + "…", displaySymbol("A".repeat(SYMBOL_CHARS - 1) + rocket + "BB"))
    }
}
