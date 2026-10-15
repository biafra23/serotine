package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The accounts of this process, held in memory. The first adapter behind
 * [AccountRepository]: enough to drive the Home screen and the hosts' composition roots
 * until a persistent store exists. Safe to call from any thread.
 *
 * The port's one-entry-per-address contract holds for [initial] too: a duplicate address
 * in it keeps the first entry, as [add] does.
 */
class InMemoryAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {

    private val accounts = MutableStateFlow(initial.distinctBy { it.address })

    override fun observeAccounts(): Flow<List<Account>> = accounts.asStateFlow()

    /** Appends [account]; an account already present (same address) is left where it is. */
    fun add(account: Account) {
        accounts.update { current ->
            if (current.any { it.address == account.address }) current else current + account
        }
    }
}
