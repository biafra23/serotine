package eth.serotine.data.keys

import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals

class Bip39EnglishWordlistTest {

    @Test
    fun isTheCanonicalListByItsHash() {
        // english.txt is the words, one per line, with a final newline.
        val file = BIP39_ENGLISH.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()

        assertEquals(2048, BIP39_ENGLISH.size)
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            SHA256().digest(file).toHex(),
        )
    }

    @Test
    fun isSortedSoABinarySearchFindsEachWordsValue() {
        assertEquals(BIP39_ENGLISH.sorted(), BIP39_ENGLISH)
        BIP39_ENGLISH.forEachIndexed { value, word -> assertEquals(value, BIP39_ENGLISH.binarySearch(word)) }
    }
}
