package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The port for the history of the transactions this wallet sent (issue #85): every transaction
 * [SentTransfers] ever recorded, kept for good, each with how it ended as far as this device saw
 * ([SentOutcome]). [SentTransfers] forgets a transaction once its nonce is mined, as it holds only
 * what may still hold a nonce back; this record is what the Activity screen lists, and what a
 * transaction's status screen reads once [SentTransfers] has forgotten it. Public data only, as
 * [SentTransfers]' is. :data keeps it in a file. Never throws.
 */
interface TransactionHistory {

    /** Every transaction in the history, re-emitted on each change, or that the history cannot be read. */
    fun observe(): Flow<HistoryList>

    /**
     * Adds [transfer] to the history, or replaces the transaction of the same hash with it (a
     * retry a peer took, a deployment's listing), keeping the outcome already recorded of it; a
     * new one starts as [SentOutcome.NotSeen]. False when it could not be saved.
     */
    suspend fun record(transfer: SentTransfer): Boolean

    /**
     * Records [outcome] as how the transaction [hash] ended, in place of the outcome recorded of
     * it; false when the history has no transaction [hash], or it could not be saved.
     */
    suspend fun settle(hash: String, outcome: SentOutcome): Boolean
}

/** What [TransactionHistory.observe] holds. */
sealed interface HistoryList {

    /** The [entries], oldest first. */
    data class Loaded(val entries: List<HistoryEntry>) : HistoryList

    /** The history cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : HistoryList
}

/** A transaction this wallet sent ([transfer], as [SentTransfers] recorded it) and how it ended ([outcome]). */
data class HistoryEntry(val transfer: SentTransfer, val outcome: SentOutcome = SentOutcome.NotSeen)

/**
 * How a sent transaction ended, as far as this device saw: what `FollowSentTransactions` records
 * from its verified receipt while the app is open. The engine finds a receipt only for a
 * transaction mined while it watches it, so one mined while the app was closed is told by its
 * nonce instead ([NonceUsed]).
 */
sealed interface SentOutcome {

    /** No verified block was seen to include it (yet). */
    data object NotSeen : SentOutcome

    /**
     * Its verified receipt in block [blockNumber], not finalized when last seen; [succeeded]
     * false when it reverted (its fee is paid all the same).
     */
    data class Included(val blockNumber: Long, val succeeded: Boolean) : SentOutcome

    /** Its verified receipt in block [blockNumber], which the beacon chain finalized: how it ended for good. */
    data class Finalized(val blockNumber: Long, val succeeded: Boolean) : SentOutcome

    /**
     * Its account's verified count of mined transactions passed its nonce while no receipt of it was
     * seen: that nonce is used on chain, by this transaction or by another that took its place,
     * which this device cannot tell apart any more. How it ended for good, as far as this device can say.
     */
    data object NonceUsed : SentOutcome
}
