package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Secp256k1RecoveryTest {

    private val recovery = Secp256k1Recovery()

    // The Sepolia Safe transaction hash of SafeTxTest's vector (pcaversaccio/safe-tx-hashes-util).
    private val hash = Digest.of("0xcb8bbe7bf8f8a1f3f57658e450d07d4422356ac042d96a87ba425b19e67a78a1")

    @Test
    fun aSignatureOfTheRawHashRecoversToItsSigner() {
        // Made by Foundry's cast with a made-up key, of the hash as given (no eth_sign prefix):
        // cast wallet sign --no-hash --private-key 0x1f2e…7988 0xcb8b…78a1, v = 27.
        val signature = Signature(
            r = "f1e0bb554c42c32fc6c3df71654fab75da6e4fe00b825ec872678870f9727a14",
            s = "5fc819df4a94b436b5da31a7c6412224ff35948507409bf7a2a500d7577dcc2f",
            recoveryId = 0,
        )

        // cast wallet address --private-key 0x1f2e…7988
        assertEquals(Address.of("0xAA06CA87eE128ec0010F6bF148Bb040F3bc07f9b"), recovery.recover(hash, signature))
    }

    @Test
    fun anotherHashOrRecoveryIdRecoversToSomeoneElse() {
        // A made-up key, signed here as the key store signs.
        val key = "1f2e3d4c5b6a7988".repeat(4).fromHex()
        val compact = Secp256k1.sign(hash.hex.fromHex(), key)
        val signature = Signature(compact.copyOfRange(0, 32).toHex(), compact.copyOfRange(32, 64).toHex(), 0)
        val signer = addressOf(key)
        val recovered = listOf(0, 1).map { recovery.recover(hash, signature.copy(recoveryId = it)) }

        assertEquals(1, recovered.count { it == signer }, "one recovery id names the key")
        val other = Digest.of("0x" + "11".repeat(32))
        assertNotEquals(signer, recovery.recover(other, signature))
    }
}
