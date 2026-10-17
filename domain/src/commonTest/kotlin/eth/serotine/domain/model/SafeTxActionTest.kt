package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SafeTxActionTest {

    private val safe = Address.of("0x" + "5a".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Address.of("0x" + "cc".repeat(20))

    private fun call(to: Address, value: Long = 0, data: ByteArray = ByteArray(0)) =
        SafeTx.call(to, UInt256.of(value), data, nonce = 1)

    private fun word(address: Address) = "0".repeat(24) + address.hex.removePrefix("0x")

    @Test
    fun aTransferIsReadFromItsFieldsAlone() {
        assertEquals(SafeTxAction.NativeTransfer(bob, UInt256.of(5)), SafeTxAction.of(safe, call(bob, value = 5)))
        assertEquals(
            SafeTxAction.TokenTransfer(usdc, bob, UInt256.of(1_500_000)),
            SafeTxAction.of(safe, call(usdc, data = Erc20.transferData(bob, UInt256.of(1_500_000)))),
        )
    }

    @Test
    fun onlyExactlyATransfersDataIsATransfer() {
        val transfer = Erc20.transferData(bob, UInt256.ONE)

        // One byte more, ether with it, or an address word with bits above its 20 bytes: a call.
        assertEquals(
            SafeTxAction.ContractCall(usdc, UInt256.ZERO, "a9059cbb", 69),
            SafeTxAction.of(safe, call(usdc, data = transfer + byteArrayOf(0))),
        )
        assertEquals(
            SafeTxAction.ContractCall(usdc, UInt256.ONE, "a9059cbb", 68),
            SafeTxAction.of(safe, call(usdc, value = 1, data = transfer)),
        )
        val dirty = transfer.copyOf().also { it[4] = 1 }
        assertEquals(
            SafeTxAction.ContractCall(usdc, UInt256.ZERO, "a9059cbb", 68),
            SafeTxAction.of(safe, call(usdc, data = dirty)),
        )
        assertEquals(
            SafeTxAction.ContractCall(usdc, UInt256.ZERO, null, 3),
            SafeTxAction.of(safe, call(usdc, data = byteArrayOf(1, 2, 3))),
        )
    }

    @Test
    fun aDelegateCallIsNeverReadAsAnythingElse() {
        val tx = SafeTx(
            usdc, UInt256.ZERO, Erc20.transferData(bob, UInt256.ONE), SafeOperation.DELEGATECALL,
            UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, nonce = 1,
        )

        assertEquals(SafeTxAction.DelegateCall(usdc, "a9059cbb", 68), SafeTxAction.of(safe, tx))
    }

    @Test
    fun aCallOfTheSafeOnItselfChangesItsSettingsOrRejects() {
        assertEquals(SafeTxAction.Rejection, SafeTxAction.of(safe, call(safe)))
        val threshold = "694e80c3" + "2".padStart(64, '0')
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.ThresholdChanged(2)),
            SafeTxAction.of(safe, call(safe, data = threshold.hexToBytes())),
        )
        val swap = "e318b52b" + word(Address.of("0x" + "00".repeat(19) + "01")) + word(bob) + word(usdc)
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.OwnerSwapped(old = bob, new = usdc)),
            SafeTxAction.of(safe, call(safe, data = swap.hexToBytes())),
        )
        val noGuard = "e19a9dd9" + word(Address.ZERO)
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.GuardSet(null)),
            SafeTxAction.of(safe, call(safe, data = noGuard.hexToBytes())),
        )
        val module = "610b5925" + word(bob)
        assertEquals(
            SafeTxAction.SettingsChange(SafeSettingsChange.ModuleEnabled(bob)),
            SafeTxAction.of(safe, call(safe, data = module.hexToBytes())),
        )
        // The same function called on another contract is that contract's business.
        assertEquals(
            SafeTxAction.ContractCall(bob, UInt256.ZERO, "694e80c3", 36),
            SafeTxAction.of(safe, call(bob, data = threshold.hexToBytes())),
        )
        // And a token's transfer called on the Safe itself is no token transfer.
        assertEquals(
            SafeTxAction.ContractCall(safe, UInt256.ZERO, "a9059cbb", 68),
            SafeTxAction.of(safe, call(safe, data = Erc20.transferData(bob, UInt256.ONE))),
        )
    }

    @Test
    fun aRefundIsSaidWhenTheGasPriceIsNotZero() {
        assertNull(SafeTxRefund.of(call(bob, value = 1)))
        val tx = SafeTx(
            bob, UInt256.ONE, ByteArray(0), SafeOperation.CALL, UInt256.of(50_000), UInt256.of(100),
            UInt256.of(10), usdc, Address.ZERO, nonce = 1,
        )

        assertEquals(
            SafeTxRefund(UInt256.of(10), UInt256.of(100), UInt256.of(50_000), gasToken = usdc, receiver = null),
            SafeTxRefund.of(tx),
        )
    }

    @Test
    fun aSigningRequestCarriesOneSignatureOfEachOwnerInOrder() {
        val tx = call(bob, value = 1)
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 0)
        val other = Signature("3".padStart(64, '0'), "4".padStart(64, '0'), 1)
        val high = SafeSignature.Ecdsa(Address.of("0x" + "ee".repeat(20)), signature)
        val low = SafeSignature.Ecdsa(Address.of("0x" + "11".repeat(20)), signature)

        val request = SigningRequest.of(Network.SEPOLIA, safe, tx, listOf(high, low))
        assertEquals(listOf(low, high), request.signatures)
        assertEquals(tx.hash(Network.SEPOLIA.chainId, safe), request.hash)

        // Merged: an owner already there keeps its signature.
        val merged = request.withSignatures(listOf(SafeSignature.Ecdsa(low.owner, other)))
        assertEquals(request, merged)
        assertFailsWith<IllegalArgumentException> { SigningRequest.of(Network.SEPOLIA, safe, tx, listOf(low, low)) }
    }
}
