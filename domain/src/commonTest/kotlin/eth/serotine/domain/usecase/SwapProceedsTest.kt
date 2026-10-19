package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SentAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwapProceedsTest {
    // The two events' signature hashes, as every explorer shows them.
    private val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val withdrawal = "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65"

    private val account = Address.of("0x" + "ac".repeat(20))
    private val router = Address.of("0x" + "50".repeat(20))
    private val pool = Address.of("0x" + "30".repeat(20))
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)

    private fun topic(address: Address) = "0x" + "00".repeat(12) + address.hex.drop(2)
    private fun amount(units: Long) = "0x" + UInt256.of(units).toBytes32().joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private val sold = TransferAsset.Erc20Token(usdc)
    private val forLink = SentAction.Swap(router, sold, UInt256.ONE, TransferAsset.Erc20Token(link), UInt256.ONE)
    private val forEther = SentAction.Swap(router, sold, UInt256.ONE, TransferAsset.Native, UInt256.ONE, weth)

    @Test
    fun aTokenBoughtIsWhatItsOwnContractSaysItTransferredToTheAccount() {
        val logs = listOf(
            // The USDC sold, to the pool: not what was bought.
            ReceiptLog(usdc.address, listOf(transfer, topic(account), topic(pool)), amount(1)),
            ReceiptLog(link.address, listOf(transfer, topic(pool), topic(account)), amount(4_300)),
            // Another contract's claim of a LINK transfer counts for nothing.
            ReceiptLog(usdc.address, listOf(transfer, topic(pool), topic(account)), amount(9_999)),
        )
        assertEquals(UInt256.of(4_300), SwapProceeds.receivedOf(forLink, account, logs))
    }

    @Test
    fun etherBoughtIsWhatTheRecordedWethUnwrappedForTheRouter() {
        val logs = listOf(
            ReceiptLog(weth, listOf(transfer, topic(pool), topic(router)), amount(16)),
            ReceiptLog(weth, listOf(withdrawal, topic(router)), amount(15)),
            ReceiptLog(link.address, listOf(withdrawal, topic(router)), amount(1_000)),
        )
        assertEquals(UInt256.of(15), SwapProceeds.receivedOf(forEther, account, logs))
        assertNull(SwapProceeds.receivedOf(forEther.copy(weth = null), account, logs), "an older record")
    }

    @Test
    fun noMatchingLogOrAMalformedAmountSaysNothing() {
        assertNull(SwapProceeds.receivedOf(forLink, account, emptyList()))
        val malformed = ReceiptLog(link.address, listOf(transfer, topic(pool), topic(account)), "0x01")
        assertNull(SwapProceeds.receivedOf(forLink, account, listOf(malformed)))
    }
}
