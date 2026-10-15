package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals

class Bip32Test {

    @Test
    fun everyPublishedVectorDerivesItsKeysAndChainCodes() {
        for ((seedHex, chain) in TestVectors.bip32) {
            val seed = seedHex.hexToBytes()
            for ((path, xprv) in chain) {
                var key = Bip32.master(seed)
                for (index in parsePath(path)) key = Bip32.child(key, index)

                assertEquals(decodeXprv(xprv), key.chainCode.toHex() to key.key.toHex(), "$seedHex at $path")
            }
        }
    }

    @Test
    fun theReferenceMnemonicSeedsHaveTheirMasterKeys() {
        for (vector in TestVectors.bip39) {
            val master = Bip32.master(vector.seedHex.hexToBytes())

            assertEquals(decodeXprv(vector.masterXprv), master.chainCode.toHex() to master.key.toHex(), vector.mnemonic)
        }
    }

    @Test
    fun deriveReturnsTheKeyAtThePath() {
        val (seedHex, chain) = TestVectors.bip32.first()
        val (path, xprv) = chain.last()

        assertEquals(decodeXprv(xprv).second, Bip32.derive(seedHex.hexToBytes(), parsePath(path)).toHex())
    }

    @Test
    fun anExtendedKeyNeverPrintsItself() {
        assertEquals("ExtendedKey(redacted)", Bip32.master(ByteArray(16)).toString())
    }
}
