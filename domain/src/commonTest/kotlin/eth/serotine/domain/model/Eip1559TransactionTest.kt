package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Known answers from two independent implementations:
 *
 * - viem (wevm/viem, src/utils/transaction/serializeTransaction.test.ts at commit 72950dd9, the
 *   `eip1559` tests): the unsigned serializations of `default`, `default (all zeros)`, `args: gas`
 *   and `args: data`, and the signed one of `signed`, whose signature viem made with its test
 *   account 0 (Anvil's and Hardhat's first key, 0xac0974be…ff80) over keccak256 of the unsigned
 *   serialization.
 * - Besu's vectors as ethereumjs carries them (ethereumjs/ethereumjs-monorepo,
 *   packages/tx/test/testData/eip1559.ts at commit e806e9d0, private key 0x8f2a5594…be63, chain 4):
 *   the signed raw transactions, there wrapped once more in an RLP string header (0xb871… — the
 *   form a block body holds), which is dropped here; the transaction hash of the first, and its
 *   r, s and yParity, from packages/tx/test/eip-1559.spec.ts (`hash()`, `toJSON()`).
 *
 * Neither publishes the signing digest itself. The two digests below are keccak256 of the
 * unsigned serialization (viem's published one; for Besu's, the signed one less its last three
 * fields), computed with OpenSSL 3.6's KECCAK-256, and each checked by recovering the signer from
 * the vector's (r, s, yParity) with an independent secp256k1: viem's to 0xf39fd6e5…2266, the
 * address of its account 0, Besu's to 0xfe3b557e…bd73, the address of the vector's key. viem's
 * transaction hash is computed the same way (viem's test does not state it). These checks ran
 * outside the build, which has no secp256k1 in :domain.
 */
class Eip1559TransactionTest {

    private val gwei = UInt256.of(1_000_000_000)
    private val ether = UInt256.of(1_000_000_000_000_000_000)
    private val viemTo = Address.of("0x70997970c51812dc3a010c7d01b50e0d17dc79c8")

    private fun viem(gasLimit: Long = 0, data: ByteArray = ByteArray(0)) = Eip1559Transaction(
        chainId = 1,
        nonce = 785,
        maxPriorityFeePerGas = UInt256.of(2) * gwei,
        maxFeePerGas = UInt256.of(2) * gwei,
        gasLimit = gasLimit,
        to = viemTo,
        value = ether,
        data = data,
    )

    private val viemUnsigned =
        "02ef0182031184773594008477359400809470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c0"
    private val viemSignature = Signature(
        r = "ce18214ff9d06ecaacb61811f9d6dc2be922e8cebddeaf6df0b30d5c498f6d33",
        s = "5f0487c6dbbf2139f7c705d8054dbb16ecac8ae6256ce2c4c6f2e7ef35b3a496",
        recoveryId = 1,
    )
    private val viemSigned =
        "02f8720182031184773594008477359400809470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c0" +
            "01a0ce18214ff9d06ecaacb61811f9d6dc2be922e8cebddeaf6df0b30d5c498f6d33" +
            "a05f0487c6dbbf2139f7c705d8054dbb16ecac8ae6256ce2c4c6f2e7ef35b3a496"

    private fun besu(nonce: Long, maxPriorityFeePerGas: Long, maxFeePerGas: Long, gasLimit: Long, value: Long) =
        Eip1559Transaction(
            chainId = 4,
            nonce = nonce,
            maxPriorityFeePerGas = UInt256.of(maxPriorityFeePerGas),
            maxFeePerGas = UInt256.of(maxFeePerGas),
            gasLimit = gasLimit,
            to = Address.of("0x000000000000000000000000000000000000aaaa"),
            value = UInt256.of(value),
        )

    @Test
    fun viemsUnsignedSerializationsAndTheirDigest() {
        assertEquals(viemUnsigned, viem().encodeUnsigned().toHex())
        assertEquals(Digest.of(keccak256(viemUnsigned.hexToBytes()).toHex()), viem().signingDigest())
        assertEquals(
            Digest.of("3d8203ae910f7ec03aae20a014030990d10a97cc96fdf9741e171951689df710"),
            viem().signingDigest(),
        )
        // args: gas — 21001 is 0x5209.
        assertEquals(
            "02f101820311847735940084773594008252099470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c0",
            viem(gasLimit = 21_001).encodeUnsigned().toHex(),
        )
        // args: data
        assertEquals(
            "02f10182031184773594008477359400809470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a7640000821234c0",
            viem(data = "1234".hexToBytes()).encodeUnsigned().toHex(),
        )
        // default (all zeros): every zero integer is the empty string, 0x80.
        val zeros = Eip1559Transaction(
            chainId = 1,
            nonce = 0,
            maxPriorityFeePerGas = UInt256.ZERO,
            maxFeePerGas = UInt256.ZERO,
            gasLimit = 0,
            to = viemTo,
            value = UInt256.ZERO,
        )
        assertEquals("02dd01808080809470997970c51812dc3a010c7d01b50e0d17dc79c88080c0", zeros.encodeUnsigned().toHex())
    }

    @Test
    fun viemsSignedSerialization() {
        val raw = viem().encodeSigned(viemSignature)
        assertEquals(viemSigned, raw.toHex())
        assertEquals(
            "0xcc495c8c8d08e5a167af93259581c34bd4fb5728f16f7ba80570459e074a2d6b",
            Eip1559Transaction.hashOf(raw),
        )
    }

    @Test
    fun besusSignedTransactionItsHashAndDigest() {
        val tx = besu(
            nonce = 819,
            maxPriorityFeePerGas = 75853,
            maxFeePerGas = 121212,
            gasLimit = 35552,
            value = 43203529,
        )
        // toJSON() gives r as 0xf924cb…: the number, here as the signature's 32 bytes.
        val signature = Signature(
            r = "0f924cb68412c8f1cfd74d9b581c71eeaf94fff6abdde3e5b02ca6b2931dcf47",
            s = "7dd1c50027c3e31f8b565e25ce68a5072110f61fce5eee81b195dd51273c2f83",
            recoveryId = 0,
        )
        val raw = tx.encodeSigned(signature)
        assertEquals(
            "02f86e048203338301284d8301d97c828ae094000000000000000000000000000000000000aaaa8402933bc980c0" +
                "80a00f924cb68412c8f1cfd74d9b581c71eeaf94fff6abdde3e5b02ca6b2931dcf47" +
                "a07dd1c50027c3e31f8b565e25ce68a5072110f61fce5eee81b195dd51273c2f83",
            raw.toHex(),
        )
        assertEquals(
            "0x2e564c87eb4b40e7f469b2eec5aa5d18b0b46a24e8bf0919439cfb0e8fcae446",
            Eip1559Transaction.hashOf(raw),
        )
        assertEquals(
            Digest.of("e0b52197916ca4528d05e8abfe4fd902e91dbd3137a423856228e737d984f6b2"),
            tx.signingDigest(),
        )
    }

    @Test
    fun besusSignedTransactionWithASingleByteNonceAndYParityOne() {
        // The last vector: nonce 119 is below 0x80, so it is its own encoding (0x77).
        val tx = besu(
            nonce = 119,
            maxPriorityFeePerGas = 24721,
            maxFeePerGas = 107729,
            gasLimit = 62341,
            value = 65456115,
        )
        val signature = Signature(
            r = "5e40977f4064a2bc08785e422ed8a47b56aa219abe93251d2b3b4d0cf937b8c0",
            s = "71e600cd15589c3607bd9627314b99e9b5976bd427b774aa685bd0d036b1771e",
            recoveryId = 1,
        )
        assertEquals(
            "02f86b04778260918301a4d182f38594000000000000000000000000000000000000aaaa8403e6c7f380c0" +
                "01a05e40977f4064a2bc08785e422ed8a47b56aa219abe93251d2b3b4d0cf937b8c0" +
                "a071e600cd15589c3607bd9627314b99e9b5976bd427b774aa685bd0d036b1771e",
            tx.encodeSigned(signature).toHex(),
        )
    }

    @Test
    fun rAndSAreEncodedAsIntegersWithoutLeadingZeroBytes() {
        // Hand-derived from the RLP rules on viem's transaction: an r of 31 significant bytes is
        // 0x9f + those bytes, one byte shorter, and the list's length (0x72 above) shrinks with it.
        val shortR = "00" + viemSignature.r.drop(2)
        val raw = viem().encodeSigned(viemSignature.copy(r = shortR))
        val unsignedFields = viemUnsigned.removePrefix("02ef")
        assertEquals(
            "02f871" + unsignedFields + "01" + "9f" + shortR.drop(2) + "a0" + viemSignature.s,
            raw.toHex(),
        )
        // An s of one is the single byte 0x01, its own encoding.
        val oneS = viem().encodeSigned(viemSignature.copy(s = "0".repeat(63) + "1"))
        assertTrue(oneS.toHex().endsWith("01" + "a0" + viemSignature.r + "01"))
    }

    @Test
    fun refusesWhatNoChainWouldTake() {
        fun make(
            chainId: Long = 1,
            nonce: Long = 0,
            priorityFee: Long = 1,
            maxFee: UInt256 = UInt256.ONE,
            gas: Long = 1,
        ) = Eip1559Transaction(chainId, nonce, UInt256.of(priorityFee), maxFee, gas, viemTo, UInt256.ZERO)
        make()
        val refused = listOf(
            "chain id 0" to { make(chainId = 0) },
            "negative chain id" to { make(chainId = -1) },
            "negative nonce" to { make(nonce = -1) },
            "negative gas limit" to { make(gas = -1) },
            "priority fee above the max fee" to { make(priorityFee = 2) },
        )
        refused.forEach { (case, build) -> assertFailsWith<IllegalArgumentException>(case) { build() } }
        // The fee cap, gasLimit × maxFeePerGas, fits in 256 bits.
        val max = UInt256.fromBigEndian(ByteArray(32) { 0xff.toByte() })
        make(maxFee = max, gas = 1)
        assertFailsWith<IllegalArgumentException> { make(maxFee = max, gas = 2) }
    }

    @Test
    fun equalByValueAndImmutable() {
        val data = "a9059cbb".hexToBytes()
        val tx = viem(data = data)
        assertEquals(viem(data = "a9059cbb".hexToBytes()), tx)
        assertEquals(viem(data = "a9059cbb".hexToBytes()).hashCode(), tx.hashCode())
        assertNotEquals(viem(data = "a9059cbc".hexToBytes()), tx)
        assertNotEquals(viem(gasLimit = 1, data = data), tx)

        data[0] = 0
        tx.data[1] = 0
        assertContentEquals("a9059cbb".hexToBytes(), tx.data)
        assertTrue(tx.toString().endsWith("data=0xa9059cbb)"), tx.toString())
    }

    @Test
    fun theIntrinsicGasIsTheBaseAndPraguesCalldataFloor() {
        assertEquals(21_000, Eip1559Transaction.intrinsicGas(ByteArray(0)))
        // Four nonzero bytes: EIP-7623's 10 × 16 tokens beats EIP-2028's 16 × 4.
        assertEquals(21_000L + 160, Eip1559Transaction.intrinsicGas(byteArrayOf(1, 2, 3, 4)))
        // An ERC-20 transfer's 68 bytes: 4 selector bytes nonzero, the rest as they come.
        val data = Erc20.transferData(Address.of("0x" + "11".repeat(20)), UInt256.ONE)
        val zeros = data.count { it == 0.toByte() }
        assertEquals(21_000L + 10 * (zeros + 4 * (data.size - zeros)), Eip1559Transaction.intrinsicGas(data))
    }
}
