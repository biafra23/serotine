package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.repository.AccountDerivation
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.Derivation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ImportMnemonicAccountTest {

    private val first = Address.of("0x" + "11".repeat(20))
    private val second = Address.of("0x" + "22".repeat(20))

    private val derivation = FakeDerivation()
    private val repository = FakeAccountRepository()
    private val import = ImportMnemonicAccount(derivation, repository)

    @Test
    fun addsTheDerivedAddressAsAnEoaNamedAfterItsPosition() = runTest {
        val mnemonic = Mnemonic("first words")
        derivation.results[mnemonic] = Derivation.Derived(first)

        val result = import(mnemonic)

        val expected = Account(first, AccountKind.EOA, "Account 1")
        assertEquals(MnemonicImport.Added(expected), result)
        assertEquals(listOf(expected), repository.accounts.value)
        assertSame(mnemonic, derivation.received.single(), "the words reach the port unchanged")
    }

    @Test
    fun theNextAccountTakesTheFirstFreeName() = runTest {
        repository.accounts.value = listOf(
            Account(Address.of("0x" + "aa".repeat(20)), AccountKind.SAFE, "Treasury"),
            Account(Address.of("0x" + "bb".repeat(20)), AccountKind.EOA, "Account 3"),
        )
        val mnemonic = Mnemonic("second words")
        derivation.results[mnemonic] = Derivation.Derived(second)

        val result = import(mnemonic)

        assertEquals(MnemonicImport.Added(Account(second, AccountKind.EOA, "Account 4")), result)
    }

    @Test
    fun anAddressAlreadyInTheWalletIsReportedAndLeftAlone() = runTest {
        val existing = Account(first, AccountKind.EOA, "Daily")
        repository.accounts.value = listOf(existing)
        val mnemonic = Mnemonic("first words again")
        derivation.results[mnemonic] = Derivation.Derived(first)

        assertEquals(MnemonicImport.AlreadyAdded(existing), import(mnemonic))
        assertEquals(listOf(existing), repository.accounts.value)
    }

    @Test
    fun invalidWordsAddNothing() = runTest {
        val mnemonic = Mnemonic("not a mnemonic")
        derivation.results[mnemonic] = Derivation.Invalid(MnemonicProblem.Checksum)

        assertEquals(MnemonicImport.Invalid(MnemonicProblem.Checksum), import(mnemonic))
        assertEquals(emptyList(), repository.accounts.value)
    }

    private class FakeDerivation : AccountDerivation {
        // Keyed by identity, which is Mnemonic's equality.
        val results = mutableMapOf<Mnemonic, Derivation>()
        val received = mutableListOf<Mnemonic>()

        override suspend fun deriveFirstAccount(mnemonic: Mnemonic): Derivation {
            received += mnemonic
            return results.getValue(mnemonic)
        }
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())

        override fun observeAccounts(): Flow<List<Account>> = accounts

        override suspend fun add(account: Account): Boolean {
            var added = false
            accounts.update { current ->
                added = current.none { it.address == account.address }
                if (added) current + account else current
            }
            return added
        }
    }
}
