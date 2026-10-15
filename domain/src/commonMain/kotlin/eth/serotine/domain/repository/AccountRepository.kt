package eth.serotine.domain.repository

import eth.serotine.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * The port through which the domain reaches the accounts the wallet manages. :data supplies
 * the implementation (today an in-memory one); the UI never sees this interface, only the use
 * cases that consume it.
 */
interface AccountRepository {
    /**
     * Every managed account in display order, re-emitted on each change. Each address
     * appears at most once — an adapter that can receive the same address twice keeps the
     * first — so consumers may key on it. Never fails.
     */
    fun observeAccounts(): Flow<List<Account>>

    /**
     * Appends [account] to the list and returns true; or, when its address is already
     * managed, leaves the existing entry exactly as it is and returns false. Never fails.
     */
    suspend fun add(account: Account): Boolean
}
