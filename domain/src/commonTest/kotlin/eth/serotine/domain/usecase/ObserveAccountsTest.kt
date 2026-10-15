package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveAccountsTest {

    private val repository = FakeAccountRepository()
    private val observeAccounts = ObserveAccounts(repository)

    @Test
    fun emitsTheRepositoryStreamUnchanged() = runTest {
        val account = Account(Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Savings")
        repository.accounts.value = listOf(account)

        assertEquals(listOf(account), observeAccounts().first())
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        override fun observeAccounts(): Flow<List<Account>> = accounts
    }
}
