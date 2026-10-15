package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ImportMnemonicAccountTest {

    private val first = Address.of("0x" + "11".repeat(20))
    private val second = Address.of("0x" + "22".repeat(20))

    private val keys = FakeKeyStore()
    private val password = WalletPassword("correct horse")
    private val repository = FakeAccountRepository()
    private val import = ImportMnemonicAccount(keys, repository)

    @Test
    fun addsTheDerivedAddressAsAnEoaNamedAfterItsPosition() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        val result = import(mnemonic, password)

        val expected = Account(first, AccountKind.EOA, "Account 1")
        assertEquals(MnemonicImport.Added(expected, KeyProtection.HARDWARE), result)
        assertEquals(listOf(expected), repository.accounts.value)
        assertSame(mnemonic, keys.received.single().first, "the words reach the key store unchanged")
        assertSame(password, keys.received.single().second, "and so does the password")
    }

    @Test
    fun theNextAccountTakesTheFirstFreeName() = runTest {
        repository.accounts.value = listOf(
            Account(Address.of("0x" + "aa".repeat(20)), AccountKind.SAFE, "Treasury"),
            Account(Address.of("0x" + "bb".repeat(20)), AccountKind.EOA, "Account 3"),
        )
        val mnemonic = Mnemonic("second words")
        keys.results[mnemonic] = KeyImport.Stored(second, KeyProtection.HARDWARE)

        val result = import(mnemonic, password)

        assertEquals(
            MnemonicImport.Added(Account(second, AccountKind.EOA, "Account 4"), KeyProtection.HARDWARE),
            result,
        )
    }

    @Test
    fun anAddressAlreadyInTheWalletIsReportedAndLeftAlone() = runTest {
        val existing = Account(first, AccountKind.EOA, "Daily")
        repository.accounts.value = listOf(existing)
        val mnemonic = Mnemonic("first words again")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        assertEquals(MnemonicImport.AlreadyAdded(existing), import(mnemonic, password))
        assertEquals(listOf(existing), repository.accounts.value)
    }

    @Test
    fun anEntryAddedAndRemovedBetweenReadAndAddIsReadAgainNotAssumed() = runTest {
        val mnemonic = Mnemonic("raced words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)
        // The first add loses to another writer, whose entry is gone again by the re-read.
        repository.refuseNextAdd = true

        val result = import(mnemonic, password)

        assertEquals(MnemonicImport.Added(Account(first, AccountKind.EOA, "Account 1"), KeyProtection.HARDWARE), result)
    }

    @Test
    fun anUnreadableListOrAFailedSaveIsNotSaved() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        repository.unreadable = true
        assertEquals(MnemonicImport.NotSaved, import(mnemonic, password))

        repository.unreadable = false
        repository.failSaves = true
        assertEquals(MnemonicImport.NotSaved, import(mnemonic, password))
        assertEquals(emptyList(), repository.accounts.value)
    }

    @Test
    fun aWrongPasswordOrAnUnwrittenEnvelopeAddsNoAccount() = runTest {
        val mnemonic = Mnemonic("first words")

        keys.results[mnemonic] = KeyImport.WrongPassword
        assertEquals(MnemonicImport.WrongPassword, import(mnemonic, password))

        keys.results[mnemonic] = KeyImport.NotSaved
        assertEquals(MnemonicImport.NotSaved, import(mnemonic, password))
        assertEquals(emptyList(), repository.accounts.value, "no account without its stored key")
    }

    @Test
    fun invalidWordsAddNothing() = runTest {
        val mnemonic = Mnemonic("not a mnemonic")
        keys.results[mnemonic] = KeyImport.Invalid(MnemonicProblem.Checksum)

        assertEquals(MnemonicImport.Invalid(MnemonicProblem.Checksum), import(mnemonic, password))
        assertEquals(emptyList(), repository.accounts.value)
    }

    @Test
    fun aDeviceTheAdapterCannotRunOnAddsNothing() = runTest {
        val mnemonic = Mnemonic("any words")
        keys.results[mnemonic] = KeyImport.Unavailable

        assertEquals(MnemonicImport.Unavailable, import(mnemonic, password))
        assertEquals(emptyList(), repository.accounts.value)
    }

    private class FakeKeyStore : KeyStore {
        // Keyed by identity, which is Mnemonic's equality.
        val results = mutableMapOf<Mnemonic, KeyImport>()
        val received = mutableListOf<Pair<Mnemonic, WalletPassword>>()

        override suspend fun protection() = KeyProtection.HARDWARE

        override suspend fun hasPassword() = received.isNotEmpty()

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport {
            received += mnemonic to password
            return results.getValue(mnemonic)
        }
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        var refuseNextAdd = false
        var unreadable = false
        var failSaves = false

        override fun observeAccounts(): Flow<AccountList> = accounts.map {
            if (unreadable) AccountList.Unreadable(AccountList.Reason.READ_FAILED) else AccountList.Loaded(it)
        }

        override suspend fun add(account: Account): AccountAdd {
            if (failSaves) return AccountAdd.NotSaved
            if (refuseNextAdd) {
                refuseNextAdd = false
                return AccountAdd.AlreadyPresent
            }
            var added = false
            accounts.update { current ->
                added = current.none { it.address == account.address }
                if (added) current + account else current
            }
            return if (added) AccountAdd.Added else AccountAdd.AlreadyPresent
        }
    }
}
