package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.TransferAsset
import kotlinx.coroutines.flow.Flow

/**
 * The port for the record of the CoW orders this device signed (docs/swaps.md, *A signed order
 * is live until it is proven dead*): each kept from the moment it is signed, before it is sent,
 * across restarts, until its end is known and it is pruned. A signed order may reach CoW whatever
 * its POST answered, so this record, not CoW's order book, is what holds a live order's tokens
 * back from Send and from another swap. :data keeps it in a file of its own. Never throws.
 */
interface CowOrders {

    /** Every recorded order, re-emitted on each change, or that the record cannot be read. */
    fun observe(): Flow<CowOrderList>

    /**
     * Records [order], replacing the one recorded under the same UID, if any. False when it could
     * not be saved, and nothing changed: an order that could not be recorded is not sent.
     */
    suspend fun record(order: CowOrderRecord): Boolean

    /**
     * Drops the orders whose end is known ([CowOrderRecord.end] set) and that were signed before
     * [signedBefore] (unix seconds); a live order is never dropped. False when the record could
     * not be saved.
     */
    suspend fun prune(signedBefore: Long): Boolean
}

/** What [CowOrders.observe] emits. */
sealed interface CowOrderList {

    /** The recorded [orders], oldest first. */
    data class Loaded(val orders: List<CowOrderRecord>) : CowOrderList

    /**
     * The record exists but cannot be read: no order is known to be live or not, so nothing may
     * be sold until it can be read.
     */
    data object Unreadable : CowOrderList
}

/**
 * One CoW order signed on this device: [order], whose UID is [uid], signed by [account] with
 * [signature] for the settlement contract [settlement], whose logs alone count for it; the swap
 * it is, [sell] for [buy] as the user chose them; signed when the verified head was at
 * [signedAt] (unix seconds), and proven from block [rangeStart] on, the block that was finalized
 * then. [placement] is what its POST answered (null: not sent yet), and [end] what the chain
 * proved of it (null: not yet).
 */
data class CowOrderRecord(
    val account: Address,
    val settlement: Address,
    val order: CowOrder,
    val uid: CowOrderUid,
    val signature: Signature,
    val sell: TransferAsset,
    val buy: TransferAsset,
    val signedAt: Long,
    val rangeStart: Long,
    val placement: CowPlacement? = null,
    val end: CowOrderEnd? = null,
) {
    init {
        require(uid.owner == account) { "an order's UID names its owner" }
        require(uid.validTo == order.validTo) { "an order's UID carries its validTo" }
        require(rangeStart >= 0) { "a block number is never negative" }
    }

    /** True until its end is known: its tokens may still be taken. */
    val live: Boolean
        get() = end == null
}

/** What the chain proved of a CoW order (docs/swaps.md, *Proving an order's end*). */
sealed interface CowOrderEnd {

    /**
     * It filled, as [trade] says: the settlement's `Trade` log in block [block], of transaction
     * [transactionHash] (`0x` and 64 lower-case hex digits).
     */
    data class Filled(val trade: CowTrade, val block: Long, val transactionHash: String) : CowOrderEnd

    /**
     * It never filled: no `Trade` from its first block to [endBlock], a finalized block past its
     * `validTo` or, when [cancelled], the block of its cancellation on chain.
     */
    data class Unfilled(val endBlock: Long, val cancelled: Boolean) : CowOrderEnd

    /**
     * Its end cannot be proven, ever: the account's log-index entry no longer covers its range
     * (unwatched, or the range reaches below the entry's start). The balance shows what happened.
     */
    data object NotProvable : CowOrderEnd
}
