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
     * The managed accounts, re-emitted on each change, or why they cannot be shown. In a
     * [AccountList.Loaded] each address appears at most once — an adapter that can receive
     * the same address twice keeps the first — so consumers may key on it. Never throws: a
     * stored list that cannot be read is [AccountList.Unreadable], never an empty list.
     */
    fun observeAccounts(): Flow<AccountList>

    /**
     * Appends [account] to the list ([AccountAdd.Added]); or, when its address is already
     * managed, leaves the existing entry exactly as it is ([AccountAdd.AlreadyPresent]); or,
     * when the list cannot be stored or is [AccountList.Unreadable], changes nothing
     * ([AccountAdd.NotSaved]). Never throws.
     */
    suspend fun add(account: Account): AccountAdd
}

/** What [AccountRepository.observeAccounts] emits. */
sealed interface AccountList {

    /**
     * The [accounts], in display order. [damagedListSetAside] is true when the stored list
     * was damaged and has been set aside, unread, under another name, so these are only
     * the accounts added since: the user should hear about it.
     */
    data class Loaded(val accounts: List<Account>, val damagedListSetAside: Boolean = false) : AccountList

    /**
     * A stored list exists but cannot be used, for the reason [reason] gives; it is left
     * untouched, and nothing can be added until it can be read.
     */
    data class Unreadable(val reason: Reason) : AccountList

    /** Why an [Unreadable] list cannot be used. */
    enum class Reason {
        /** Written by a newer version of the app, in a format this one does not know. */
        NEWER_FORMAT,

        /** The file could not be read, or a damaged one could not be set aside. */
        READ_FAILED,
    }
}

/** The outcome of [AccountRepository.add]. */
sealed interface AccountAdd {
    /** The account is in the list now. */
    data object Added : AccountAdd

    /** Its address was in the list already; the existing entry is unchanged. */
    data object AlreadyPresent : AccountAdd

    /** The list could not be stored, or cannot be read; nothing changed. */
    data object NotSaved : AccountAdd
}
