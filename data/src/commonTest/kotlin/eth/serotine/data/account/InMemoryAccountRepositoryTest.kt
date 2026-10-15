package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryAccountRepositoryTest {

    private val eoa = Account(Address.of("0x" + "aa".repeat(20)), AccountKind.EOA, "Daily")
    private val safe = Account(Address.of("0x" + "bb".repeat(20)), AccountKind.SAFE, "Treasury")

    @Test
    fun startsWithTheInitialAccountsAndEmitsAdditions() = runTest {
        val repository = InMemoryAccountRepository(listOf(eoa))
        assertEquals(listOf(eoa), repository.observeAccounts().first())

        assertTrue(repository.add(safe))
        assertEquals(listOf(eoa, safe), repository.observeAccounts().first())
    }

    @Test
    fun duplicateAddressesInTheInitialAccountsKeepTheFirstEntry() = runTest {
        val repository = InMemoryAccountRepository(listOf(eoa, eoa.copy(label = "Renamed"), safe))

        assertEquals(listOf(eoa, safe), repository.observeAccounts().first())
    }

    @Test
    fun addingAnAddressTwiceKeepsTheFirstEntry() = runTest {
        val repository = InMemoryAccountRepository()
        assertTrue(repository.add(eoa))
        assertFalse(repository.add(eoa.copy(label = "Renamed")))

        assertEquals(listOf(eoa), repository.observeAccounts().first())
    }
}
