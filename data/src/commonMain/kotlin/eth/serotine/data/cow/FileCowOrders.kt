package eth.serotine.data.cow

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.data.transfers.StoredToken
import eth.serotine.data.transfers.asset
import eth.serotine.data.transfers.storedToken
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderList
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowOrders
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [CowOrders] kept in the file at [path] (a [VersionedJsonFile]): each CoW order signed on this
 * device, from the moment it is signed (docs/swaps.md, *A signed order is live until it is proven
 * dead*), with its signature, which is public once posted, and how its POST and its end stand.
 * Public data only: no key, no password.
 *
 * An entry that does not decode (a damaged one, or one a newer build wrote) is passed over and the
 * rest are read; the file it was in is then set aside once, whole, and what was read written in its
 * place, so that the entry is kept there rather than lost at the next write. A file that is not
 * one at all is set aside and the record starts empty; one that cannot be read is
 * [CowOrderList.Unreadable], and nothing may then be sold that a live order could hold.
 */
class FileCowOrders(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CowOrders {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "CoW orders",
        formatVersion = FORMAT_VERSION,
        empty = emptyList<CowOrderRecord>(),
        decode = { _, text -> JSON.decodeFromString(StoredFile.serializer(), text).orders.mapNotNull(::decoded) },
        incomplete = { text, orders ->
            JSON.decodeFromString(StoredFile.serializer(), text).orders.size != orders.size
        },
        encode = { orders ->
            val entries = orders.map { JSON.encodeToJsonElement(StoredCowOrder.serializer(), stored(it)) }
            JSON.encodeToString(StoredFile.serializer(), StoredFile(FORMAT_VERSION, entries))
        },
    )

    override fun observe(): Flow<CowOrderList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> CowOrderList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> CowOrderList.Unreadable
        }
    }

    override suspend fun record(order: CowOrderRecord): Boolean = file.change(notSaved = false) { orders ->
        Decision(true, next = orders.filterNot { it.uid == order.uid } + order)
    }

    override suspend fun end(uid: CowOrderUid, end: CowOrderEnd): Boolean = file.change(notSaved = false) { orders ->
        if (orders.none { it.uid == uid }) return@change Decision(false)
        Decision(true, next = orders.map { if (it.uid == uid) it.copy(end = end) else it })
    }

    override suspend fun prune(signedBefore: Long): Boolean = file.change(notSaved = false) { orders ->
        val kept = orders.filterNot { !it.live && it.signedAt < signedBefore }
        if (kept.size == orders.size) Decision(true) else Decision(true, next = kept)
    }

    @Serializable
    private class StoredFile(val version: Int, val orders: List<JsonElement>)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}

/**
 * One order as stored: its account, network (by chain id) and settlement contract; the order's
 * fields; its UID and signature; the swap it is ([sell], [buy]: a token, or none for native ether
 * bought); when it was signed and the block its proof starts at; how its POST stands; its end.
 */
@Serializable
internal class StoredCowOrder(
    val account: String,
    val chainId: Long,
    val settlement: String,
    val sellToken: String,
    val buyToken: String,
    val receiver: String,
    val sellAmount: String,
    val buyAmount: String,
    val validTo: Long,
    val uid: String,
    val signature: StoredSignature,
    val sell: StoredToken? = null,
    val buy: StoredToken? = null,
    val signedAt: Long,
    val rangeStart: Long,
    val placement: StoredPlacement,
    val end: StoredEnd? = null,
)

/** An order's signature: `r`, `s` (lower-case hex) and its recovery id. */
@Serializable
internal class StoredSignature(val r: String, val s: String, val recoveryId: Int)

/** How a POST stands: [kind] (`attempted`, `accepted`, `refused`, `unclear`), and why ([why], [errorType]). */
@Serializable
internal class StoredPlacement(val kind: String, val why: String? = null, val errorType: String? = null)

/**
 * An order's end: [kind] (`filled`, `unfilled`, `not-provable`); a fill's [trade], [block] and
 * [transactionHash]; or the block an unfilled order's range ended at ([block]) and whether it was
 * [cancelled].
 */
@Serializable
internal class StoredEnd(
    val kind: String,
    val trade: StoredTrade? = null,
    val block: Long? = null,
    val transactionHash: String? = null,
    val cancelled: Boolean? = null,
)

/** A `Trade` log's fields. */
@Serializable
internal class StoredTrade(
    val owner: String,
    val sellToken: String,
    val buyToken: String,
    val sellAmount: String,
    val buyAmount: String,
    val feeAmount: String,
    val uid: String,
)

/** The order [entry] stores, or null when it does not decode: passed over, the rest read. */
private fun decoded(entry: JsonElement): CowOrderRecord? = try {
    val stored = Json.decodeFromJsonElement(StoredCowOrder.serializer(), entry)
    val network = requireNotNull(Network.entries.firstOrNull { it.chainId == stored.chainId }) { "a network" }
    CowOrderRecord(
        account = Address.of(stored.account),
        network = network,
        settlement = Address.of(stored.settlement),
        order = CowOrder(
            sellToken = Address.of(stored.sellToken),
            buyToken = Address.of(stored.buyToken),
            receiver = Address.of(stored.receiver),
            sellAmount = UInt256.of(stored.sellAmount),
            buyAmount = UInt256.of(stored.buyAmount),
            validTo = stored.validTo,
        ),
        uid = CowOrderUid.of(stored.uid),
        signature = Signature(stored.signature.r, stored.signature.s, stored.signature.recoveryId),
        sell = stored.sell.asset(),
        buy = stored.buy.asset(),
        signedAt = stored.signedAt,
        rangeStart = stored.rangeStart,
        placement = placementOf(stored.placement),
        end = stored.end?.let(::endOf),
    )
} catch (e: IllegalArgumentException) {
    // A SerializationException (a field missing or of another type), or a field out of range
    // (an address, an amount, a UID that is not the order's) as the stored types refuse it.
    null
}

private fun placementOf(stored: StoredPlacement): CowPlacement = when (stored.kind) {
    "attempted" -> CowPlacement.Attempted
    "accepted" -> CowPlacement.Accepted(CowOrderUid.of(requireNotNull(stored.why) { "an accepted UID" }))
    "refused" -> CowPlacement.Refused(
        CowRefusal.valueOf(requireNotNull(stored.why) { "a refusal's kind" }),
        requireNotNull(stored.errorType) { "a refusal's error type" },
    )
    "unclear" -> CowPlacement.Unclear(CowUnavailable.valueOf(requireNotNull(stored.why) { "why it is unclear" }))
    else -> throw IllegalArgumentException("a placement of no kind")
}

private fun endOf(stored: StoredEnd): CowOrderEnd = when (stored.kind) {
    "filled" -> {
        val trade = requireNotNull(stored.trade) { "a fill's trade" }
        CowOrderEnd.Filled(
            trade = CowTrade(
                owner = Address.of(trade.owner),
                sellToken = Address.of(trade.sellToken),
                buyToken = Address.of(trade.buyToken),
                sellAmount = UInt256.of(trade.sellAmount),
                buyAmount = UInt256.of(trade.buyAmount),
                feeAmount = UInt256.of(trade.feeAmount),
                uid = CowOrderUid.of(trade.uid),
            ),
            block = requireNotNull(stored.block) { "a fill's block" },
            transactionHash = requireNotNull(stored.transactionHash) { "a fill's transaction" },
        )
    }
    "unfilled" -> CowOrderEnd.Unfilled(
        endBlock = requireNotNull(stored.block) { "the range's end" },
        cancelled = requireNotNull(stored.cancelled) { "whether it was cancelled" },
    )
    "not-provable" -> CowOrderEnd.NotProvable
    else -> throw IllegalArgumentException("an end of no kind")
}

/** The form [order] is stored in. */
private fun stored(order: CowOrderRecord) = StoredCowOrder(
    account = order.account.hex,
    chainId = order.network.chainId,
    settlement = order.settlement.hex,
    sellToken = order.order.sellToken.hex,
    buyToken = order.order.buyToken.hex,
    receiver = order.order.receiver.hex,
    sellAmount = order.order.sellAmount.toDecimal(),
    buyAmount = order.order.buyAmount.toDecimal(),
    validTo = order.order.validTo,
    uid = order.uid.hex,
    signature = StoredSignature(order.signature.r, order.signature.s, order.signature.recoveryId),
    sell = order.sell.storedToken(),
    buy = order.buy.storedToken(),
    signedAt = order.signedAt,
    rangeStart = order.rangeStart,
    placement = when (val placement = order.placement) {
        CowPlacement.Attempted -> StoredPlacement("attempted")
        is CowPlacement.Accepted -> StoredPlacement("accepted", why = placement.uid.hex)
        is CowPlacement.Refused -> StoredPlacement("refused", why = placement.why.name, errorType = placement.errorType)
        is CowPlacement.Unclear -> StoredPlacement("unclear", why = placement.why.name)
    },
    end = when (val end = order.end) {
        null -> null
        is CowOrderEnd.Filled -> StoredEnd(
            kind = "filled",
            trade = with(end.trade) {
                StoredTrade(
                    owner.hex,
                    sellToken.hex,
                    buyToken.hex,
                    sellAmount.toDecimal(),
                    buyAmount.toDecimal(),
                    feeAmount.toDecimal(),
                    uid.hex,
                )
            },
            block = end.block,
            transactionHash = end.transactionHash,
        )
        is CowOrderEnd.Unfilled -> StoredEnd("unfilled", block = end.endBlock, cancelled = end.cancelled)
        CowOrderEnd.NotProvable -> StoredEnd("not-provable")
    },
)
