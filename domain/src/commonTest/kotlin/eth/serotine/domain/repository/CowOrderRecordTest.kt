package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowAppData
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CowOrderRecordTest {

    private val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val account = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val order = CowOrder(
        sellToken = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14"),
        buyToken = Address.of("0xbe72e441bf55620febc26715db68d3494213d8cb"),
        receiver = account,
        sellAmount = UInt256.of(10),
        buyAmount = UInt256.of(6),
        validTo = 1_792_665_588,
        appData = CowAppData.HASH,
    )
    private val signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "1", 0)

    private fun record(owner: Address = account, uidOrder: CowOrder = order) = CowOrderRecord(
        account = account,
        settlement = settlement,
        order = order,
        uid = uidOrder.uid(Network.SEPOLIA.chainId, settlement, owner),
        signature = signature,
        sell = TransferAsset.Native,
        buy = TransferAsset.Native,
        signedAt = 1_792_665_288,
        rangeStart = 11_968_800,
    )

    @Test
    fun anOrderIsLiveUntilItsEndIsKnown() {
        assertTrue(record().live)
        assertFalse(record().copy(end = CowOrderEnd.Unfilled(endBlock = 11_968_900, cancelled = false)).live)
        assertFalse(record().copy(end = CowOrderEnd.NotProvable).live)
    }

    @Test
    fun theUidMustBeTheAccountsAndCarryTheOrdersValidTo() {
        assertFailsWith<IllegalArgumentException>("another owner") {
            record(owner = Address.of("0x0000000000000000000000000000000000000001"))
        }
        assertFailsWith<IllegalArgumentException>("another validTo") { record(uidOrder = order.copy(validTo = 1)) }
    }
}
