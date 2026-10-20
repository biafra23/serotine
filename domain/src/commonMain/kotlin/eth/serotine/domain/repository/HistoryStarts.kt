package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.Flow

/**
 * The port for where each account's history starts (issue #85): the block from which the
 * [LogIndex] follows the account. An account is noted when it is added, with the time
 * ([HistoryStart.Noted]); `FollowAccountLogs` fixes the block once the light client has a head
 * to measure it from ([HistoryStart.Fixed]), and forgets the start once the account is gone and
 * the index stopped following it. The history is checked from the fixed block on and not before
 * it: for an account not created here, that is the block it was added at (owner ruling,
 * 2026-10-20). Public data only. :data keeps it in a file. Never throws.
 */
interface HistoryStarts {

    /** Every start held, re-emitted on each change, or that they cannot be read. */
    fun observe(): Flow<HistoryStartList>

    /**
     * Notes that [address] was added to the wallet at [atSeconds] (unix seconds), unless a start
     * is held for it already, which is kept. False when it could not be saved.
     */
    suspend fun note(address: Address, atSeconds: Long): Boolean

    /**
     * Fixes [address]'s start at [block], in place of a [HistoryStart.Noted] one or none; a
     * start fixed already is kept, as the index may have walked from it. False when it could
     * not be saved, or a start was fixed already.
     */
    suspend fun fix(address: Address, block: Long): Boolean

    /** Forgets [address]'s start; true when none is held for it any more. */
    suspend fun forget(address: Address): Boolean
}

/** What [HistoryStarts.observe] holds. */
sealed interface HistoryStartList {

    /** The [starts], by account. */
    data class Loaded(val starts: Map<Address, HistoryStart>) : HistoryStartList

    /** The starts cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : HistoryStartList
}

/** Where an account's history starts. */
sealed interface HistoryStart {

    /** The account was added at [atSeconds] (unix seconds); its block is not fixed yet. */
    data class Noted(val atSeconds: Long) : HistoryStart

    /** The history is followed from block [block] on. */
    data class Fixed(val block: Long) : HistoryStart {
        init {
            require(block >= 0) { "a block number is never negative" }
        }
    }
}
