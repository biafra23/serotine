package eth.serotine.domain.repository

import eth.serotine.domain.model.Account
import kotlinx.coroutines.flow.Flow

/**
 * The port through which the domain reaches the accounts the wallet manages. :data supplies
 * the implementation (today an in-memory one); the UI never sees this interface, only the use
 * cases that consume it.
 */
interface AccountRepository {
    /** Every managed account in display order, re-emitted on each change. Never fails. */
    fun observeAccounts(): Flow<List<Account>>
}
