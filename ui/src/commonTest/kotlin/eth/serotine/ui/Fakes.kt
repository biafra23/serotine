package eth.serotine.ui

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NativeBalances
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** An [AccountRepository] over a list a test sets; [unreadable] and [failSaves] make it fail. */
internal class FakeAccounts(vararg initial: Account) : AccountRepository {
    val accounts = MutableStateFlow(initial.toList())
    val unreadable = MutableStateFlow(false)
    var failSaves = false

    override fun observeAccounts(): Flow<AccountList> = combine(accounts, unreadable) { list, broken ->
        if (broken) AccountList.Unreadable(AccountList.Reason.READ_FAILED) else AccountList.Loaded(list)
    }

    override suspend fun add(account: Account): AccountAdd = when {
        failSaves -> AccountAdd.NotSaved
        accounts.value.any { it.address == account.address } -> AccountAdd.AlreadyPresent
        else -> {
            accounts.value = accounts.value + account
            AccountAdd.Added
        }
    }

    override suspend fun rename(address: Address, label: String): AccountChange =
        change(address) { list -> list.map { if (it.address == address) it.copy(label = label) else it } }

    override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange =
        change(address) { list -> list.map { if (it.address == address) it.copy(custody = custody) else it } }

    override suspend fun remove(address: Address): AccountChange =
        change(address) { list -> list.filterNot { it.address == address } }

    private fun change(address: Address, edit: (List<Account>) -> List<Account>): AccountChange = when {
        failSaves -> AccountChange.NotSaved
        accounts.value.none { it.address == address } -> AccountChange.NotFound
        else -> {
            accounts.value = edit(accounts.value)
            AccountChange.Changed
        }
    }
}

/** A [KeyStore] whose deletions are recorded, and fail when [failDeletes]. */
internal class FakeKeys : KeyStore {
    val deleted = mutableListOf<Address>()
    var failDeletes = false

    override suspend fun describe(): KeyStoreDescription = error("not used by these tests")

    override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
        error("not used by these tests")

    override suspend fun delete(address: Address): KeyDeletion {
        deleted += address
        return if (failDeletes) KeyDeletion.Failed else KeyDeletion.Deleted
    }
}

/** [NativeBalances] whose reading per address a test sets; Syncing until then. */
internal class FakeBalances : NativeBalances {
    private val readings = mutableMapOf<Address, MutableStateFlow<BalanceReading>>()

    fun of(address: Address) = readings.getOrPut(address) { MutableStateFlow(BalanceReading.Syncing) }

    override fun observeBalance(address: Address): Flow<BalanceReading> = of(address)
}
