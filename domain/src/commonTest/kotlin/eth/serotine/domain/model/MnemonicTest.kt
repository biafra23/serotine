package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnemonicTest {

    @Test
    fun keepsTheTextAsTypedAndReadsNormalizedWords() {
        val typed = "  Abandon\tABOUT \n zoo  "
        val mnemonic = Mnemonic(typed)

        assertEquals(typed, mnemonic.text)
        assertEquals(listOf("abandon", "about", "zoo"), mnemonic.words)
    }

    @Test
    fun blankTextHasNoWords() {
        assertEquals(emptyList(), Mnemonic.EMPTY.words)
        assertEquals(emptyList(), Mnemonic(" \n ").words)
    }

    @Test
    fun toStringNeverPrintsTheWords() {
        val mnemonic = Mnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow")

        assertEquals("Mnemonic(redacted)", mnemonic.toString())
        // Nor does the generated toString of a data class that holds one.
        data class Holder(val phrase: Mnemonic)
        assertFalse("legal" in Holder(mnemonic).toString())
    }

    @Test
    fun equalityIsIdentitySoEqualsCannotCompareSecrets() {
        val words = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        val mnemonic = Mnemonic(words)

        assertTrue(mnemonic == mnemonic)
        assertFalse(mnemonic == Mnemonic(words))
    }
}
