package eth.serotine.data.keys

import eth.serotine.domain.model.MnemonicProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Bip39Test {

    @Test
    fun everyReferenceVectorIsValidAndStretchesToItsSeed() {
        for (vector in TestVectors.bip39) {
            val words = vector.mnemonic.split(' ')

            assertNull(Bip39.problemWith(words), vector.mnemonic)
            assertEquals(vector.seedHex, Bip39.seed(words, TestVectors.BIP39_PASSPHRASE).toHex(), vector.mnemonic)
        }
    }

    @Test
    fun theEmptyPassphraseIsTheDefault() {
        // The well-known seed of the all-"abandon" mnemonic with no passphrase.
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(' ')

        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Bip39.seed(words).toHex(),
        )
    }

    @Test
    fun aWordCountBip39DoesNotDefineIsRefused() {
        val elevenWords = List(11) { "abandon" }

        assertEquals(MnemonicProblem.WordCount(11), Bip39.problemWith(elevenWords))
        assertEquals(MnemonicProblem.WordCount(0), Bip39.problemWith(emptyList()))
        assertEquals(MnemonicProblem.WordCount(25), Bip39.problemWith(List(25) { "zoo" }))
    }

    @Test
    fun theFirstWordNotInTheListIsNamedByPosition() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandonn abandon about"
            .split(' ')

        assertEquals(MnemonicProblem.UnknownWord(position = 10), Bip39.problemWith(words))
    }

    @Test
    fun aWrongChecksumIsRefused() {
        // All twelve words are list words, but zero entropy's checksum word is "about".
        assertEquals(MnemonicProblem.Checksum, Bip39.problemWith(List(12) { "abandon" }))
        // The first two words swapped in a valid 24-word vector.
        val valid = TestVectors.bip39.map { it.mnemonic.split(' ') }.first { it.size == 24 && it[0] != it[1] }
        val swapped = listOf(valid[1], valid[0]) + valid.drop(2)
        assertEquals(MnemonicProblem.Checksum, Bip39.problemWith(swapped))
    }

    @Test
    fun aPassphraseThatNeedsNormalizationIsRefusedNotStretched() {
        assertFailsWith<IllegalArgumentException> { Bip39.seed(List(12) { "abandon" }, passphrase = "café") }
    }

    @Test
    fun entropyBecomesTheWordsOfTheReferenceVectors() {
        // trezor/python-mnemonic vectors.json: the entropy of the first four English vectors.
        mapOf(
            "00".repeat(16) to List(11) { "abandon" }.joinToString(" ") + " about",
            "7f".repeat(16) to "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "80".repeat(16) to "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            "ff".repeat(16) to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
        ).forEach { (entropy, words) ->
            assertEquals(words, Bip39.wordsOf(entropy.fromHex()).joinToString(" "))
            assertTrue(TestVectors.bip39.any { it.mnemonic == words }, "a published vector")
        }
    }

    @Test
    fun theWordsOfAnyEntropyAreAValidMnemonic() {
        repeat(32) {
            val words = Bip39.wordsOf(kotlin.random.Random.nextBytes(16))
            assertEquals(12, words.size)
            assertEquals(null, Bip39.problemWith(words))
        }
    }
}
