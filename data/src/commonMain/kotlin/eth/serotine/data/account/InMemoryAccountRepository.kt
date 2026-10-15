package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The accounts of this process, held in memory: an [AccountRepository] for tests and
 * previews, which nothing outlives. The hosts use [FileAccountRepository]. Safe to call
 * from any thread; never answers [AccountAdd.NotSaved].
 *
 * The port's one-entry-per-address contract holds for [initial] too: a duplicate address
 * in it keeps the first entry, as [add] does.
 */
class InMemoryAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {

    private val accounts = MutableStateFlow(initial.distinctBy { it.address })

    override fun observeAccounts(): Flow<List<Account>> = accounts.asStateFlow()

    override suspend fun add(account: Account): AccountAdd {
        var added = false
        accounts.update { current ->
            // Recomputed on every retry of update's compare-and-set, so it describes the
            // list that was actually written.
            added = current.none { it.address == account.address }
            if (added) current + account else current
        }
        return if (added) AccountAdd.Added else AccountAdd.AlreadyPresent
    }
}
