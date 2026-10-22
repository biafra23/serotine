package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The accounts of this process, held in memory: an [AccountRepository] for tests and
 * previews, which nothing outlives. The hosts use [FileAccountRepository]. Safe to call
 * from any thread; never answers [AccountAdd.NotSaved] or [AccountChange.NotSaved].
 *
 * The port's one-entry-per-address contract holds for [initial] too: a duplicate address
 * in it keeps the first entry, as [add] does.
 */
class InMemoryAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {

    private val accounts = MutableStateFlow(initial.distinctBy { it.address })

    override fun observeAccounts(): Flow<AccountList> = accounts.map { AccountList.Loaded(it) }

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

    override suspend fun rename(address: Address, label: String): AccountChange =
        changeEntry(address) { current ->
            current.map { if (it.address == address) it.copy(label = label, ensName = null) else it }
        }

    override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange =
        changeEntry(address) { current ->
            current.map { if (it.address == address) it.copy(custody = custody) else it }
        }

    override suspend fun remove(address: Address): AccountChange =
        changeEntry(address) { current -> current.filterNot { it.address == address } }

    /** Applies [change] when an entry has [address]; NotFound, with nothing changed, when none does. */
    private fun changeEntry(address: Address, change: (List<Account>) -> List<Account>): AccountChange {
        var found = false
        accounts.update { current ->
            found = current.any { it.address == address }
            if (found) change(current) else current
        }
        return if (found) AccountChange.Changed else AccountChange.NotFound
    }
}
