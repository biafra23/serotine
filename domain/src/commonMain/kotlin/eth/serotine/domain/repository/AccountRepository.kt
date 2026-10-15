package eth.serotine.domain.repository

import eth.serotine.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * The port through which the domain reaches the accounts the wallet manages: addresses, kinds
 * and labels, never key material. :data supplies the implementation (a file the list
 * survives restarts in); the UI never sees this interface, only the use cases that consume it.
 */
interface AccountRepository {
    /**
     * Every managed account in display order, re-emitted on each change. Each address
     * appears at most once — an adapter that can receive the same address twice keeps the
     * first — so consumers may key on it. Never fails.
     */
    fun observeAccounts(): Flow<List<Account>>

    /**
     * Appends [account] to the list ([AccountAdd.Added]); or, when its address is already
     * managed, leaves the existing entry exactly as it is ([AccountAdd.AlreadyPresent]); or,
     * when the new list cannot be stored, changes nothing ([AccountAdd.NotSaved]). Never
     * throws.
     */
    suspend fun add(account: Account): AccountAdd
}

/** The outcome of [AccountRepository.add]. */
sealed interface AccountAdd {
    /** The account is in the list now. */
    data object Added : AccountAdd

    /** Its address was in the list already; the existing entry is unchanged. */
    data object AlreadyPresent : AccountAdd

    /** The list could not be stored; nothing changed. */
    data object NotSaved : AccountAdd
}
