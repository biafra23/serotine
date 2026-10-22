package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * GPv2's encoding against independent vectors: the constants of `GPv2Order.sol`
 * (cowprotocol/contracts), CoW's own documented hashes, and a real order settled on Sepolia, whose
 * UID (from CoW's API) begins with its digest, recomputed with Foundry's `cast` as well.
 */
class CowProtocolTest {

    private val settlement = Address.of("0x9008D19f58AAbD9eD0D60971565AA8510560ab41")
    private val weth = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14")
    private val cow = Address.of("0xbe72e441bf55620febc26715db68d3494213d8cb")
    private val owner = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val ownerTopic = "0x000000000000000000000000" + owner.hex.removePrefix("0x")
    private val stranger = "0x000000000000000000000000" + "11".repeat(20)

    // The real order: 0.01 WETH for at least 6.16 COW, partially fillable, CoW Swap's app data.
    private val realUid = CowOrderUid.of(
        "0xacb9d41182bc7e7aba0f333a517d842ec8298235fdbcb9290d519a2c63457a4a" +
            "9fa3c00a92ec5f96b1ad2527ab41b3932efeda58" + "6ad9e7f4",
    )

    @Test
    fun theTypeHashesAreGPv2OrdersConstants() {
        assertEquals(
            "d5a25ba2e97094ad7d83dc28a6572da797d6b3e7fc6663bd93efb789fc17e489",
            GPv2Signing.ORDER_TYPEHASH.toHex(),
        )
        assertEquals(
            "f3b277728b3fee749481eb3e0b3b48980dbbab78658fc419025cb16eee346775",
            GPv2Signing.KIND_SELL.toHex(),
        )
        assertEquals(
            "5a28e9363bb942b639270062aa6bb295f434bcdfc42c97267bf003f272060dc9",
            GPv2Signing.BALANCE_ERC20.toHex(),
        )
    }

    @Test
    fun theDomainSeparatorIsTheSettlementsOwn() {
        // Mainnet's, as CoW publishes it; Sepolia's, recomputed with cast.
        assertEquals(
            "c078f884a2676e1345748b1feace7b0abee5d00ecadb6e574dcdd109a63e8943",
            GPv2Signing.domainSeparator(1, settlement).toHex(),
        )
        assertEquals(
            "daee378bd0eb30ddf479272accf91761e697bc00e067a268f95f1d2732ed230b",
            GPv2Signing.domainSeparator(Network.SEPOLIA.chainId, settlement).toHex(),
        )
    }

    @Test
    fun aRealSepoliaOrdersDigestIsTheStartOfItsUid() {
        val struct = GPv2Signing.structHash(
            sellToken = weth,
            buyToken = cow,
            receiver = owner,
            sellAmount = UInt256.of("10000000000000000"),
            buyAmount = UInt256.of("6160502866468600017"),
            validTo = 1_792_665_588,
            appData = "0x1cdb05cece6bda2b996c03c7dd6fefee626f2d19861181f0d38d57c35794a6e6",
            feeAmount = UInt256.ZERO,
            partiallyFillable = true,
        )
        val digest = GPv2Signing.digest(GPv2Signing.domainSeparator(Network.SEPOLIA.chainId, settlement), struct)
        assertEquals(realUid.digest, digest)
        assertEquals(realUid, CowOrderUid.of(digest, owner, 1_792_665_588))
        for (appData in listOf("0x" + "1c".repeat(16), "0x" + "1c".repeat(64))) {
            assertFailsWith<IllegalArgumentException>("app data of ${appData.length / 2 - 1} bytes") {
                GPv2Signing.structHash(
                    weth, cow, owner, UInt256.ONE, UInt256.ONE, 1, appData, UInt256.ZERO, partiallyFillable = false,
                )
            }
        }
    }

    @Test
    fun anOrderSerotineSignsIsFillOrKillWithNoFeeAndTheEmptyAppData() {
        val order = CowOrder(
            sellToken = weth,
            buyToken = cow,
            receiver = owner,
            sellAmount = UInt256.of("10000000000000000"),
            buyAmount = UInt256.of("6160502866468600017"),
            validTo = 1_792_665_588,
        )
        // The same order fill-or-kill, with the app data "{}": its digest, recomputed with cast.
        val digest = Digest.of("a447c0a84fdc7fc7299b995ae00ecd3d644a00e2d0f3aca4440acef12c674266")
        assertEquals(digest, order.digest(Network.SEPOLIA.chainId, settlement))
        assertEquals(
            CowOrderUid.of(digest, owner, 1_792_665_588),
            order.uid(Network.SEPOLIA.chainId, settlement, owner),
        )
    }

    @Test
    fun theAppDataIsTheEmptyDocumentWithCowsDocumentedHash() {
        assertEquals("{}", CowAppData.DOCUMENT)
        assertEquals("0xb48d38f93eaa084033fc5970bf96e559c33c4cdc07d889ab00b4d63f9590739d", CowAppData.HASH)
    }

    @Test
    fun aUidPacksTheDigestTheOwnerAndValidTo() {
        assertEquals(owner, realUid.owner)
        assertEquals(1_792_665_588L, realUid.validTo)
        assertEquals(56, realUid.bytes().size)
        assertEquals(realUid, CowOrderUid.parse(realUid.hex.uppercase().replace("0X", "0x")))
        assertNull(CowOrderUid.parse("0x1234"))
        assertNull(CowOrderUid.parse(realUid.hex + "00"))
        assertNull(CowOrderUid.parse("0x0X" + realUid.hex.removePrefix("0x")), "a doubled prefix")
        assertFailsWith<IllegalArgumentException> {
            CowOrderUid.of(realUid.digest, owner, CowOrder.UINT32_MAX + 1)
        }
    }

    @Test
    fun anOrderIsRefusedWhenItCannotBeSigned() {
        fun order(sell: Address = weth, buy: Address = cow, sold: Long = 1, bought: Long = 1, validTo: Long = 1) =
            CowOrder(sell, buy, owner, UInt256.of(sold), UInt256.of(bought), validTo)
        order()
        order(buy = CowOrder.NATIVE_BUY_TOKEN)
        assertFailsWith<IllegalArgumentException>("native ether sold") { order(sell = CowOrder.NATIVE_BUY_TOKEN) }
        assertFailsWith<IllegalArgumentException>("nothing sold") { order(sold = 0) }
        assertFailsWith<IllegalArgumentException>("no minimum: the token given away") { order(bought = 0) }
        assertFailsWith<IllegalArgumentException>("a token for itself") { order(buy = weth) }
        assertFailsWith<IllegalArgumentException>("past a uint32") { order(validTo = CowOrder.UINT32_MAX + 1) }
    }

    @Test
    fun theSettlementsCallsAreAsCastEncodesThem() {
        val tail = "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000038" +
            realUid.hex.removePrefix("0x") + "0000000000000000"
        assertEquals("15337bc0$tail", GPv2Settlement.invalidateOrder(realUid).toHex())
        assertEquals("2479fb6e$tail", GPv2Settlement.filledAmount(realUid).toHex())
        assertEquals("f698da25", GPv2Settlement.domainSeparator().toHex())
        assertEquals(UInt256.of(7), GPv2Settlement.filledAmountOf("0".repeat(63) + "7"))
        assertNull(GPv2Settlement.filledAmountOf("07"))
    }

    @Test
    fun aDomainSeparatorAnswerIsOneWord() {
        val sepolia = "daee378bd0eb30ddf479272accf91761e697bc00e067a268f95f1d2732ed230b"
        assertContentEquals(sepolia.hexToBytes(), GPv2Settlement.domainSeparatorOf(sepolia))
        assertNull(GPv2Settlement.domainSeparatorOf(sepolia.dropLast(2)), "short")
        assertNull(GPv2Settlement.domainSeparatorOf(sepolia + "00".repeat(32)), "two words")
    }

    // A real Trade log, served by the engine's log index on Sepolia (block 11,968,857, log 69).
    private val tradeData = listOf(
        "000000000000000000000000fff9976782d46cc05630d1f6ebab18b2324d6b14",
        "000000000000000000000000be72e441bf55620febc26715db68d3494213d8cb",
        "0000000000000000000000000000000000000000000000000de0b6b3a7640000",
        "000000000000000000000000000000000000000000000021f60dc5c8d7c61502",
        "0000000000000000000000000000000000000000000000000000000000000000",
        "00000000000000000000000000000000000000000000000000000000000000c0",
        "0000000000000000000000000000000000000000000000000000000000000038",
        "6fe3ce901da392fd0649b8167ad002f4ed0d6d7d509b1e84838d91dd9f3a6ab6",
        "9fa3c00a92ec5f96b1ad2527ab41b3932efeda586ad9e7d50000000000000000",
    )
    private val tradeLog = Log(
        address = settlement,
        topics = listOf(GPv2Settlement.TRADE_TOPIC, ownerTopic),
        data = "0x" + tradeData.joinToString(""),
    )

    /** The trade's data with word [at] replaced by [word]. */
    private fun tradeWith(at: Int, word: String) =
        tradeLog.copy(data = "0x" + tradeData.toMutableList().apply { set(at, word) }.joinToString(""))

    @Test
    fun aRealTradeLogDecodes() {
        assertEquals(
            CowTrade(
                owner = owner,
                sellToken = weth,
                buyToken = cow,
                sellAmount = UInt256.of("1000000000000000000"),
                buyAmount = UInt256.of("626472599206846928130"),
                feeAmount = UInt256.ZERO,
                uid = CowOrderUid.of(
                    "0x6fe3ce901da392fd0649b8167ad002f4ed0d6d7d509b1e84838d91dd9f3a6ab6" +
                        "9fa3c00a92ec5f96b1ad2527ab41b3932efeda58" + "6ad9e7d5",
                ),
            ),
            tradeLog.trade(settlement),
        )
    }

    @Test
    fun aTradeLogOfAnotherShapeOrContractIsNone() {
        val other = Address.of("0x0000000000000000000000000000000000000001")
        val trade = GPv2Settlement.TRADE_TOPIC
        assertNull(tradeLog.trade(other), "another contract's log")
        assertNull(tradeLog.copy(address = other).trade(settlement), "emitted elsewhere")
        assertNull(
            tradeLog.copy(topics = listOf(GPv2Settlement.ORDER_INVALIDATED_TOPIC, ownerTopic)).trade(settlement),
            "another event",
        )
        assertNull(tradeLog.copy(topics = listOf(trade)).trade(settlement), "no owner")
        assertNull(tradeLog.copy(topics = tradeLog.topics + ownerTopic).trade(settlement), "three topics")
        assertNull(
            tradeLog.copy(topics = listOf(trade, "0x01" + ownerTopic.drop(4))).trade(settlement),
            "an owner topic with its upper bytes set",
        )
        assertNull(tradeLog.copy(topics = listOf(trade, stranger)).trade(settlement), "a UID of another owner")
        assertNull(tradeWith(0, "01" + tradeData[0].drop(2)).trade(settlement), "a token word, upper bytes set")
        assertNull(tradeWith(5, "0".repeat(62) + "a0").trade(settlement), "the UID at another offset")
        assertNull(tradeWith(6, "0".repeat(62) + "37").trade(settlement), "a UID of 55 bytes")
        assertNull(tradeLog.copy(data = tradeLog.data.dropLast(2)).trade(settlement), "cut short")
        assertNull(tradeLog.copy(data = tradeLog.data.dropLast(2) + "01").trade(settlement), "padding set")
        assertNull(tradeLog.copy(data = tradeLog.data + "00").trade(settlement), "longer")
        assertNull(tradeLog.copy(data = tradeLog.data + "0").trade(settlement), "odd length")
        assertNull(tradeLog.copy(data = tradeLog.data.replaceFirst('0', 'g')).trade(settlement), "not hex")
        assertNull(tradeLog.copy(data = "0x").trade(settlement), "empty")
    }

    private val invalidationLog = Log(
        address = settlement,
        topics = listOf(GPv2Settlement.ORDER_INVALIDATED_TOPIC, ownerTopic),
        data = "0x" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000038" +
            realUid.hex.removePrefix("0x") + "0000000000000000",
    )

    @Test
    fun anInvalidationLogDecodesToItsOrder() {
        assertEquals(realUid, invalidationLog.invalidation(settlement))
        assertNull(invalidationLog.trade(settlement), "an invalidation is no trade")
    }

    @Test
    fun anInvalidationLogOfAnotherShapeOrContractIsNone() {
        val log = invalidationLog
        val trade = listOf(GPv2Settlement.TRADE_TOPIC, ownerTopic)
        assertNull(log.copy(address = Address.of("0x" + "22".repeat(20))).invalidation(settlement), "elsewhere")
        assertNull(log.copy(topics = listOf(log.topics[0], stranger)).invalidation(settlement), "another owner's")
        assertNull(log.copy(topics = trade).invalidation(settlement), "another event")
        assertNull(log.copy(topics = log.topics + ownerTopic).invalidation(settlement), "three topics")
        assertNull(log.copy(data = log.data.replaceFirst("20", "40")).invalidation(settlement), "another offset")
        assertNull(log.copy(data = log.data.replaceFirst("38", "37")).invalidation(settlement), "a UID of 55 bytes")
        assertNull(log.copy(data = log.data.dropLast(2) + "01").invalidation(settlement), "padding set")
        assertNull(log.copy(data = log.data + "00".repeat(32)).invalidation(settlement), "longer")
        assertNull(log.copy(data = "0x20").invalidation(settlement), "shorter than a word")
    }

    /** A log as a receipt carries it: who emitted it, its topics, its data. */
    private data class Log(val address: Address, val topics: List<String>, val data: String) {
        fun trade(settlement: Address) = GPv2Settlement.tradeOf(settlement, address, topics, data)
        fun invalidation(settlement: Address) = GPv2Settlement.invalidationOf(settlement, address, topics, data)
    }
}
