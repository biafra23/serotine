package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The Safe transaction hash against published vectors, each re-derived independently with
 * Foundry's `cast` (its own Keccak-256 and ABI encoder: `cast keccak`, `cast abi-encode`) when
 * these tests were written, and the `execTransaction` encoding against `cast calldata`.
 */
class SafeTxTest {

    private val none = Address.ZERO

    private fun tx(
        to: String,
        value: Long,
        data: String,
        nonce: Long,
        operation: SafeOperation = SafeOperation.CALL,
        gasToken: Address = none,
        refundReceiver: Address = none,
    ) = SafeTx(
        to = Address.of(to),
        value = UInt256.of(value),
        data = data.removePrefix("0x").hexToBytes(),
        operation = operation,
        safeTxGas = UInt256.ZERO,
        baseGas = UInt256.ZERO,
        gasPrice = UInt256.ZERO,
        gasToken = gasToken,
        refundReceiver = refundReceiver,
        nonce = nonce,
    )

    @Test
    fun theTypeHashesAreSafes() {
        // safe-smart-account's DOMAIN_SEPARATOR_TYPEHASH (1.3.0 on) and SAFE_TX_TYPEHASH.
        assertEquals(
            "47e79534a245952e8b16893a336b85a3d9ea9fa8c573f3d803afb92a79469218",
            SafeTx.DOMAIN_TYPEHASH.toHex(),
        )
        assertEquals(
            "bb8310d486368db6bd6f849402fdd73ad53d316b5a4b2644ad6efe0f941286d8",
            SafeTx.SAFE_TX_TYPEHASH.toHex(),
        )
    }

    @Test
    fun safeEthPysVectorOf130() {
        // safe-eth-py, safe_eth/safe/tests/test_safe_tx.py, test_hash_safe_multisig_tx_1_3 (Rinkeby, chain 4).
        val tx = tx(
            to = "0x79613FD49472C3C7a32188e45ff00e7bdC8a897d",
            value = 2,
            data = "0x1212",
            nonce = 17,
            gasToken = Address.of("0xFA995c7a0d32A4e7497508a5C380369BE8dB49Db"),
            refundReceiver = Address.of("0x6b92f5E5360bfCa8F5d3FcE65D87382967847983"),
        )

        assertEquals(
            Digest.of("0x01d42a801ac44d3ebd43592be980dea0756d7f7b27d718d3016a42ea7ce85587"),
            tx.hash(chainId = 4, safe = Address.of("0x2B0b9cBDA3D7b0F760187c52A8FFB18C48E5d96A")),
        )
    }

    @Test
    fun aSepoliaTransferAndTheApproveHashOfItFromANestedSafe() {
        // pcaversaccio/safe-tx-hashes-util, README.md, "Nested Safes": a Safe on Sepolia sends
        // 0.1 ETH at nonce 4 (domain hash 0x611379c1…, message hash 0x565bba8b…) ...
        val primary = Address.of("0x657ff0D4eC65D82b2bC1247b0a558bcd2f80A0f1")
        val transfer = tx(to = "0x255C3912f91eF11bFDadd405F13144a823Da8cc5", value = 100_000_000_000_000_000, "0x", 4)
        val hash = transfer.hash(Network.SEPOLIA.chainId, primary)
        assertEquals(Digest.of("0xcb8bbe7bf8f8a1f3f57658e450d07d4422356ac042d96a87ba425b19e67a78a1"), hash)

        // ... and its owner, itself a Safe, approves that hash on chain, also at nonce 4.
        val approveData = SafeCalls.approveHash(hash)
        assertEquals(
            "d4d9bdcdcb8bbe7bf8f8a1f3f57658e450d07d4422356ac042d96a87ba425b19e67a78a1",
            approveData.toHex(),
        )
        val approval = tx(to = primary.hex, value = 0, data = approveData.toHex(), nonce = 4)
        assertEquals(
            Digest.of("0x86eb3f93f2670d119a4ecb8eeaa4dafe31a28abcafe06688d47e195a3dd7abb0"),
            approval.hash(Network.SEPOLIA.chainId, Address.of("0x6bc56d6CE87C86CB0756c616bECFD3Cd32b09251")),
        )
    }

    @Test
    fun anOwnerAddedOnArbitrumHashesAndDecodesAsSuch() {
        // pcaversaccio/safe-tx-hashes-util, README.md, "Safe Transaction Hashes": the Safe calls
        // itself with addOwnerWithThreshold(0x0c75…c9e5, 3) at nonce 234 on Arbitrum (chain 42161).
        val safe = Address.of("0x111CEEee040739fD91D29C34C33E6B3E112F2177")
        val tx = tx(
            to = safe.hex,
            value = 0,
            data = "0x0d582f130000000000000000000000000c75fa5a5f1c0997e3eea425cfa13184ed0ec9e5" +
                "0000000000000000000000000000000000000000000000000000000000000003",
            nonce = 234,
        )

        assertEquals(
            Digest.of("0x0cb7250b8becd7069223c54e2839feaed4cee156363fbfe5dd0a48e75c4e25b3"),
            tx.hash(42_161, safe),
        )
        assertEquals(
            SafeTxAction.SettingsChange(
                SafeSettingsChange.OwnerAdded(Address.of("0x0c75fa5a5f1c0997e3eea425cfa13184ed0ec9e5"), 3),
            ),
            SafeTxAction.of(safe, tx),
        )
    }

    @Test
    fun everyFieldIsInTheHash() {
        val safe = Address.of("0x" + "5a".repeat(20))
        val base = tx(to = "0x" + "bb".repeat(20), value = 1, data = "0x", nonce = 0)
        val hash = base.hash(Network.SEPOLIA.chainId, safe)

        fun variant(
            to: Address = base.to,
            value: UInt256 = base.value,
            data: ByteArray = base.data,
            operation: SafeOperation = base.operation,
            safeTxGas: UInt256 = base.safeTxGas,
            baseGas: UInt256 = base.baseGas,
            gasPrice: UInt256 = base.gasPrice,
            gasToken: Address = base.gasToken,
            refundReceiver: Address = base.refundReceiver,
            nonce: Long = base.nonce,
        ) = SafeTx(to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, nonce)
        val variants = listOf(
            variant(to = Address.of("0x" + "bc".repeat(20))),
            variant(value = UInt256.of(2)),
            variant(data = byteArrayOf(0)),
            variant(operation = SafeOperation.DELEGATECALL),
            variant(safeTxGas = UInt256.ONE),
            variant(baseGas = UInt256.ONE),
            variant(gasPrice = UInt256.ONE),
            variant(gasToken = safe),
            variant(refundReceiver = safe),
            variant(nonce = 1),
        )

        for (variant in variants) assertNotEquals(hash, variant.hash(Network.SEPOLIA.chainId, safe), "$variant")
        assertNotEquals(hash, base.hash(1, safe), "the chain id is in the domain")
        assertNotEquals(hash, base.hash(Network.SEPOLIA.chainId, Address.of("0x" + "5b".repeat(20))))
    }

    @Test
    fun execTransactionIsEncodedAsCastEncodesIt() {
        // cast calldata "execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)"
        //   0x255C…8cc5 100000000000000000 0x1212 0 0 0 0 0x0…0 0x0…0 0x<A approved ‖ B signed>
        val ownerA = Address.of("0x" + "11".repeat(20))
        val ownerB = Address.of("0x" + "22".repeat(20))
        val signed = SafeSignature.Ecdsa(ownerB, Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 1))
        val tx = tx(to = "0x255C3912f91eF11bFDadd405F13144a823Da8cc5", value = 100_000_000_000_000_000, "0x1212", 4)

        // Given in either order: packed by owner.
        val data = SafeCalls.execTransaction(tx, listOf(signed, SafeSignature.ApprovedHash(ownerA)))

        val expected = "6a761202" +
            "000000000000000000000000255c3912f91ef11bfdadd405f13144a823da8cc5" +
            "000000000000000000000000000000000000000000000000016345785d8a0000" +
            "0000000000000000000000000000000000000000000000000000000000000140" +
            "0".repeat(64 * 6) +
            "0000000000000000000000000000000000000000000000000000000000000180" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "1212000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000082" +
            "0000000000000000000000001111111111111111111111111111111111111111" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "01" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "1c" +
            "0".repeat(60)
        assertEquals(expected, data.toHex())
    }

    @Test
    fun signaturesArePackedOnePerOwnerAndAnEcdsaOnesVIs27PlusItsRecoveryId() {
        val owner = Address.of("0x" + "11".repeat(20))
        val signature = Signature("ab".repeat(32).replaceFirst("a", "7"), "2".padStart(64, '0'), 0)
        val encoded = SafeSignature.Ecdsa(owner, signature).encode()

        assertEquals(65, encoded.size)
        assertEquals(27, encoded.last().toInt())
        assertContentEquals(signature.r.hexToBytes(), encoded.copyOf(32))
        assertFailsWith<IllegalArgumentException> {
            SafeCalls.packSignatures(listOf(SafeSignature.ApprovedHash(owner), SafeSignature.Ecdsa(owner, signature)))
        }
    }

    @Test
    fun aSafeTxIsEqualByValueAndKeepsItsOwnData() {
        val data = byteArrayOf(1, 2)
        val zero = UInt256.ZERO
        val to = Address.of("0x" + "bb".repeat(20))
        val tx = SafeTx(to, zero, data, SafeOperation.CALL, zero, zero, zero, none, none, nonce = 3)
        data[0] = 9
        tx.data[0] = 9

        assertContentEquals(byteArrayOf(1, 2), tx.data)
        assertEquals(tx, tx(to = "0x" + "bb".repeat(20), value = 0, data = "0102", nonce = 3))
        assertFailsWith<IllegalArgumentException> { tx(to = "0x" + "bb".repeat(20), value = 0, "0x", nonce = -1) }
    }
}
