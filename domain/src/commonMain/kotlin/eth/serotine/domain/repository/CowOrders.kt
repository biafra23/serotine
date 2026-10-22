package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.CowTrade
import eth.serotine.domain.model.Network
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
 * [signature] on [network] for the settlement contract [settlement], whose logs alone count for
 * it; the swap it is, [sell] for [buy] as the user chose them (the order's own tokens, native
 * ether bought as CoW's marker); signed when the verified head was at [signedAt] (unix seconds),
 * and proven from block [rangeStart] on, the block that was finalized then. [placement] is how its
 * POST stands: [CowPlacement.Attempted] from before the request on, then CoW's answer. [end] is
 * what the chain proved of it (null: not yet).
 */
data class CowOrderRecord(
    val account: Address,
    val network: Network,
    val settlement: Address,
    val order: CowOrder,
    val uid: CowOrderUid,
    val signature: Signature,
    val sell: TransferAsset,
    val buy: TransferAsset,
    val signedAt: Long,
    val rangeStart: Long,
    val placement: CowPlacement = CowPlacement.Attempted,
    val end: CowOrderEnd? = null,
) {
    init {
        require(uid == order.uid(network.chainId, settlement, account)) {
            "the UID is this order's, signed by the account"
        }
        require(order.receiver == account) { "an order pays the account that signs it" }
        val soldToken = (sell as? TransferAsset.Erc20Token)?.token?.address
        require(soldToken == order.sellToken) { "the token sold is the order's" }
        val boughtToken = (buy as? TransferAsset.Erc20Token)?.token?.address ?: CowOrder.NATIVE_BUY_TOKEN
        require(boughtToken == order.buyToken) { "the asset bought is the order's" }
        require((buy as? TransferAsset.Erc20Token)?.token?.address != CowOrder.NATIVE_BUY_TOKEN) {
            "native ether bought is recorded as TransferAsset.Native, never as a token at CoW's marker"
        }
        require(rangeStart >= 0) { "a block number is never negative" }
    }

    /** True until its end is known. Its tokens may be held back for less long: see [canStillTake]. */
    val live: Boolean
        get() = end == null

    /**
     * Whether this order may still take the account's tokens: its end is not known, and no
     * finalized block is past its `validTo` ([finalized], the newest finalized block; null: none
     * known). An order past that can fill no more, proven or not, so the hold-back of its tokens
     * ends there (docs/swaps.md, *A signed order is live until it is proven dead*).
     */
    fun canStillTake(finalized: FinalizedBlock?): Boolean =
        live && (finalized == null || finalized.timestamp <= order.validTo)
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
