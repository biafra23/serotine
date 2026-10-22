package eth.serotine.data.cow

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileCowOrdersTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-cow-${Random.nextLong().toULong()}")
    private val file = Path(directory, "cow-orders.json")
    private val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val account = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = "usdc")

    private fun TestScope.orders() = FileCowOrders(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun record(validTo: Long = 1_792_670_000, buysEther: Boolean = true): CowOrderRecord {
        val buyToken = if (buysEther) CowOrder.NATIVE_BUY_TOKEN else Address.of("0x" + "77".repeat(20))
        val order = CowOrder(usdc.address, buyToken, account, UInt256.of(100_000_000), UInt256.of(5), validTo)
        val buy = if (buysEther) {
            TransferAsset.Native
        } else {
            TransferAsset.Erc20Token(Token(buyToken, "LINK", "ChainLink Token", 18, icon = null))
        }
        return CowOrderRecord(
            account = account,
            network = Network.SEPOLIA,
            settlement = settlement,
            order = order,
            uid = order.uid(Network.SEPOLIA.chainId, settlement, account),
            signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "2", 1),
            sell = TransferAsset.Erc20Token(usdc),
            buy = buy,
            signedAt = validTo - 300,
            rangeStart = 11_968_800,
        )
    }

    private suspend fun TestScope.stored(): List<CowOrderRecord> =
        assertIs<CowOrderList.Loaded>(orders().observe().first()).orders

    @Test
    fun anOrderIsKeptAcrossARestartWithItsPlacementAndItsEnd() = runTest {
        val attempted = record()
        assertTrue(orders().record(attempted))
        assertEquals(listOf(attempted), stored())

        val accepted = attempted.copy(placement = CowPlacement.Accepted(attempted.uid))
        assertTrue(orders().record(accepted))
        assertEquals(listOf(accepted), stored(), "replaced by its UID")

        val other = record(validTo = 1_792_671_000, buysEther = false)
            .copy(placement = CowPlacement.Refused(CowRefusal.FUNDS, "InsufficientBalance"))
        val unclear = record(validTo = 1_792_672_000).copy(placement = CowPlacement.Unclear(CowUnavailable.TIMEOUT))
        orders().record(other)
        orders().record(unclear)
        assertEquals(listOf(accepted, other, unclear), stored())

        val answered = other.copy(placement = CowPlacement.Accepted(other.uid))
        orders().record(answered)
        assertEquals(listOf(accepted, answered, unclear), stored(), "recorded again in its place: oldest first")
    }

    @Test
    fun reasonsAreStoredByCodesOfTheirOwnAndOneNotKnownIsReadAsTheNearest() = runTest {
        val refused = record(validTo = 1_792_671_000)
            .copy(placement = CowPlacement.Refused(CowRefusal.FUNDS, "InsufficientBalance"))
        val unclear = record(validTo = 1_792_672_000).copy(placement = CowPlacement.Unclear(CowUnavailable.TIMEOUT))
        orders().record(refused)
        orders().record(unclear)
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue("\"why\":\"funds\"" in text && "\"why\":\"timeout\"" in text, text)

        // As a newer build might write them: reasons this one does not know.
        val newer = text.replace("\"why\":\"funds\"", "\"why\":\"rate-limited\"")
            .replace("\"why\":\"timeout\"", "\"why\":\"captive-portal\"")
        SystemFileSystem.sink(file).buffered().use { it.writeString(newer) }
        assertEquals(
            listOf(
                refused.copy(placement = CowPlacement.Refused(CowRefusal.OTHER, "InsufficientBalance")),
                unclear.copy(placement = CowPlacement.Unclear(CowUnavailable.SERVER_ERROR)),
            ),
            stored(),
            "read, not passed over: the orders stay held",
        )
    }

    @Test
    fun anEndIsSetKeepingTheRecordAsItIsByThen() = runTest {
        val order = record()
        orders().record(order)
        orders().record(order.copy(placement = CowPlacement.Accepted(order.uid)))
        val trade = CowTrade(
            owner = account,
            sellToken = usdc.address,
            buyToken = CowOrder.NATIVE_BUY_TOKEN,
            sellAmount = UInt256.of(100_000_000),
            buyAmount = UInt256.of(6),
            feeAmount = UInt256.ZERO,
            uid = order.uid,
        )
        val filled = CowOrderEnd.Filled(trade, block = 11_968_860, transactionHash = "0x" + "75".repeat(32))

        assertTrue(orders().end(order.uid, filled))

        assertEquals(listOf(order.copy(placement = CowPlacement.Accepted(order.uid), end = filled)), stored())
        assertFalse(orders().end(record(validTo = 1).uid, CowOrderEnd.NotProvable), "no such order")
        for (end in listOf(CowOrderEnd.Unfilled(11_968_900, cancelled = true), CowOrderEnd.NotProvable)) {
            orders().end(order.uid, end)
            assertEquals(end, stored().single().end)
        }
    }

    @Test
    fun pruningDropsOnlyEndedOrdersSignedBeforeTheTime() = runTest {
        val ended = record(validTo = 1_792_670_000).copy(end = CowOrderEnd.Unfilled(1, cancelled = false))
        val live = record(validTo = 1_792_660_000)
        val recent = record(validTo = 1_792_690_000).copy(end = CowOrderEnd.NotProvable)
        listOf(ended, live, recent).forEach { orders().record(it) }

        assertTrue(orders().prune(signedBefore = 1_792_680_000))

        assertEquals(listOf(live, recent), stored())
    }

    @Test
    fun anEntryThatDoesNotDecodeIsPassedOverAndTheRestRead() = runTest {
        val kept = record()
        orders().record(kept)
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        val entry = text.substringAfter("\"orders\":[").removeSuffix("]}")
        // The same entry with another minimum: its UID is no longer its order's, which the record refuses.
        val altered = entry.replace("\"buyAmount\":\"5\"", "\"buyAmount\":\"6\"")
        SystemFileSystem.sink(file).buffered().use { it.writeString(text.removeSuffix("]}") + ",$altered]}") }

        assertEquals(listOf(kept), stored())
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") }, "copied aside once, whole")
    }

    @Test
    fun fieldsANewerBuildAddedArePassedOverNotTheOrders() = runTest {
        val kept = record()
        orders().record(kept)
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        // A field in the file and one in the entry, as a newer build of the same format might write them.
        val newer = text.replace("{\"version\":1,", "{\"version\":1,\"written\":\"later\",")
            .replace("\"rangeStart\":", "\"tip\":{\"bps\":5},\"rangeStart\":")
        assertTrue(newer != text && "\"tip\"" in newer && "\"written\"" in newer, newer)
        SystemFileSystem.sink(file).buffered().use { it.writeString(newer) }

        assertEquals(listOf(kept), stored(), "still held back: the order is read")
        assertFalse(SystemFileSystem.list(directory).any { it.name.contains("damaged") }, "nothing set aside")
    }

    @Test
    fun aNewerFormatIsUnreadable() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":2,"orders":[]}""") }
        assertEquals(CowOrderList.Unreadable, orders().observe().first())
        assertFalse(orders().record(record()))
    }
}
