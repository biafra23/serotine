package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CowOrderRecordTest {

    private val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val account = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val weth = Token(Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14"), "WETH", "WETH", 18, null)
    private val cow = Token(Address.of("0xbe72e441bf55620febc26715db68d3494213d8cb"), "COW", "CoW", 18, null)
    private val order = CowOrder(
        sellToken = weth.address,
        buyToken = cow.address,
        receiver = account,
        sellAmount = UInt256.of(10),
        buyAmount = UInt256.of(6),
        validTo = 1_792_665_588,
    )
    private val signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0)

    private fun record(
        order: CowOrder = this.order,
        sell: TransferAsset = TransferAsset.Erc20Token(weth),
        buy: TransferAsset = TransferAsset.Erc20Token(cow),
        uid: CowOrderUid = order.uid(Network.SEPOLIA.chainId, settlement, account),
    ) = CowOrderRecord(
        account = account,
        network = Network.SEPOLIA,
        settlement = settlement,
        order = order,
        uid = uid,
        signature = signature,
        sell = sell,
        buy = buy,
        signedAt = 1_792_665_288,
        rangeStart = 11_968_800,
    )

    @Test
    fun anOrderIsRecordedAsAttemptedBeforeItIsSent() {
        assertEquals(CowPlacement.Attempted, record().placement)
    }

    @Test
    fun anOrderIsLiveUntilItsEndIsKnown() {
        assertTrue(record().live)
        assertFalse(record().copy(end = CowOrderEnd.Unfilled(endBlock = 11_968_900, cancelled = false)).live)
        assertFalse(record().copy(end = CowOrderEnd.NotProvable).live)
    }

    @Test
    fun itTakesNoMoreOnceAFinalizedBlockIsPastItsValidity() {
        assertTrue(record().canStillTake(null))
        assertTrue(record().canStillTake(FinalizedBlock(11_968_900, timestamp = order.validTo)))
        assertFalse(record().canStillTake(FinalizedBlock(11_968_901, timestamp = order.validTo + 1)))
        assertFalse(record().copy(end = CowOrderEnd.NotProvable).canStillTake(null))
    }

    @Test
    fun theRecordMustBeItsOrdersAndTheAccounts() {
        val stranger = Address.of("0x0000000000000000000000000000000000000001")
        assertFailsWith<IllegalArgumentException>("another owner's UID") {
            record(uid = order.uid(Network.SEPOLIA.chainId, settlement, stranger))
        }
        assertFailsWith<IllegalArgumentException>("another order's UID") {
            record(uid = order.copy(buyAmount = UInt256.of(7)).uid(Network.SEPOLIA.chainId, settlement, account))
        }
        assertFailsWith<IllegalArgumentException>("another chain's UID") {
            record(uid = order.uid(1, settlement, account))
        }
        assertFailsWith<IllegalArgumentException>("paid to another account") {
            val elsewhere = order.copy(receiver = stranger)
            record(order = elsewhere, uid = elsewhere.uid(Network.SEPOLIA.chainId, settlement, account))
        }
        assertFailsWith<IllegalArgumentException>("another token sold") { record(sell = TransferAsset.Native) }
        assertFailsWith<IllegalArgumentException>("another asset bought") { record(buy = TransferAsset.Native) }
        val forEther = order.copy(buyToken = CowOrder.NATIVE_BUY_TOKEN)
        val forEtherUid = forEther.uid(Network.SEPOLIA.chainId, settlement, account)
        record(order = forEther, buy = TransferAsset.Native, uid = forEtherUid)
        assertFailsWith<IllegalArgumentException>("native ether as a token at CoW's marker") {
            val marker = Token(CowOrder.NATIVE_BUY_TOKEN, "ETH", "Ether", 18, null)
            record(order = forEther, buy = TransferAsset.Erc20Token(marker), uid = forEtherUid)
        }
    }
}
