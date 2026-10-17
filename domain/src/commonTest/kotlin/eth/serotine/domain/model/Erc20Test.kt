package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Hand-checked against the Solidity ABI specification: a four-byte selector, then one 32-byte word per argument. */
class Erc20Test {

    @Test
    fun theTransferSelectorIsTheSignaturesKeccakPrefix() {
        val selector = keccak256("transfer(address,uint256)".encodeToByteArray()).copyOfRange(0, 4).toHex()
        assertEquals("a9059cbb", selector)
        val data = Erc20.transferData(Address.of("0x" + "00".repeat(20)), UInt256.ZERO)
        assertEquals(selector, data.copyOfRange(0, 4).toHex())
    }

    @Test
    fun transferIsTheSelectorThenTheAddressAndTheAmountAsWords() {
        // One token of 18 decimals, 10^18 = 0x0de0b6b3a7640000, to 0x7099…79c8.
        val data = Erc20.transferData(
            Address.of("0x70997970C51812dc3A010C7d01b50e0d17dc79C8"),
            UInt256.parseUnits("1", 18)!!,
        )
        assertEquals(68, data.size)
        assertEquals(
            "a9059cbb" +
                "00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8" +
                "0000000000000000000000000000000000000000000000000de0b6b3a7640000",
            data.toHex(),
        )
    }

    @Test
    fun theLargestAmountFillsItsWord() {
        val max = UInt256.fromBigEndian(ByteArray(32) { 0xff.toByte() })
        val data = Erc20.transferData(Address.of("0x" + "ab".repeat(20)), max)
        assertEquals("a9059cbb" + "00".repeat(12) + "ab".repeat(20) + "ff".repeat(32), data.toHex())
    }
}
