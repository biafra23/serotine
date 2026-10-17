package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for what became of a transaction once it was sent: its receipt, verified against
 * the receipts root of the block that includes it, and that block's finality, as the embedded
 * light client sees them. :data implements it over the Myotis engine. Never fails.
 */
interface ReceiptTracker {
    /**
     * Where the transaction [hash] (`0x` + 64 hex) stands, re-emitted as the verified head
     * moves until it is finalized; consecutive equal readings are not repeated.
     */
    fun observe(hash: String): Flow<TransactionProgress>
}

/** One answer of [ReceiptTracker.observe]. */
sealed interface TransactionProgress {

    /** No verified block includes it yet. */
    data object Pending : TransactionProgress

    /**
     * Included in block [blockNumber], its receipt verified; [succeeded] false when it
     * reverted (its fee is paid all the same).
     */
    data class Included(val blockNumber: Long, val succeeded: Boolean) : TransactionProgress

    /** Included in block [blockNumber], which the beacon chain has finalized. */
    data class Finalized(val blockNumber: Long, val succeeded: Boolean) : TransactionProgress

    /** Where it stands cannot be verified right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : TransactionProgress
}
