package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import kotlinx.coroutines.flow.Flow

/**
 * The port for native-currency balances, as the embedded light client proves them. :data
 * implements it over the Myotis engine (`eth.serotine.data.engine`), on the one network the
 * app works on (CLAUDE.md, *Data sources*); nothing else may answer it — no RPC provider, no
 * indexer, no cached guess (CLAUDE.md, *Trust*).
 */
interface NativeBalances {
    /**
     * The balance of [address], re-emitted when the verified head moves or the light
     * client's state changes; consecutive equal readings are not repeated. A
     * [BalanceReading.Verified] was proven against the sync committee at its block; when
     * no proof is possible the reading says why. Never fails.
     */
    fun observeBalance(address: Address): Flow<BalanceReading>
}

/** One answer of [NativeBalances.observeBalance]. */
sealed interface BalanceReading {

    /** The light client has no verified head yet. */
    data object Syncing : BalanceReading

    /** [wei] is the balance at block [blockNumber], verified by the light client. */
    data class Verified(val wei: Wei, val blockNumber: Long) : BalanceReading

    /** No verified balance can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: Reason) : BalanceReading

    /** Why a balance, or another verified read ([KindReading]), is unavailable. */
    enum class Reason {
        /**
         * This device has no running light client: the engine does not ship for its ABI or
         * platform, or it failed to start.
         */
        NO_ENGINE,

        /**
         * The light client refuses to sync: its best trust anchor is older than the
         * weak-subjectivity bound, and accepting that risk is the user's decision.
         */
        STALE_ANCHOR,

        /** The light client is synced but could not prove this read (no peer served the proof). */
        UNPROVEN,
    }
}
