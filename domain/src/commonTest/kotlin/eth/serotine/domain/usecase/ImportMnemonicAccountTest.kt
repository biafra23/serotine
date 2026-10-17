package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun keyed(address: Address, label: String) =
        Account(address, AccountKind.EOA, label, AccountCustody.InKeyStore)

    @Test
    fun addsTheDerivedAddressAsAnEoaNamedAfterItsPosition() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        val result = import(mnemonic, password)

        val expected = Account(first, AccountKind.EOA, "Account 1", AccountCustody.InKeyStore)
        assertEquals(MnemonicImport.Added(expected, KeyProtection.HARDWARE), result)
        assertEquals(listOf(expected), repository.accounts.value)
        assertSame(mnemonic, keys.received.single().first, "the words reach the key store unchanged")
        assertSame(password, keys.received.single().second, "and so does the password")
    }

    @Test
    fun theNextAccountTakesTheFirstFreeName() = runTest {
        repository.accounts.value = listOf(
            Account(Address.of("0x" + "aa".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.InKeyStore),
            Account(Address.of("0x" + "bb".repeat(20)), AccountKind.EOA, "Account 3", AccountCustody.InKeyStore),
        )
        val mnemonic = Mnemonic("second words")
        keys.results[mnemonic] = KeyImport.Stored(second, KeyProtection.HARDWARE)

        val result = import(mnemonic, password)

        assertEquals(
            MnemonicImport.Added(keyed(second, "Account 4"), KeyProtection.HARDWARE),
            result,
        )
    }

    @Test
    fun aWatchedAddressWhosePhraseIsImportedIsHeldFromThenOn() = runTest {
        val watched = Account(first, AccountKind.EOA, "Savings", AccountCustody.Watched)
        repository.accounts.value = listOf(watched)
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        val held = watched.copy(custody = AccountCustody.InKeyStore)
        assertEquals(MnemonicImport.KeyAdded(held), import(mnemonic, password))
        assertEquals(listOf(held), repository.accounts.value, "the same entry, its name kept")
    }

    @Test
    fun anAddressAlreadyInTheWalletIsReportedAndLeftAlone() = runTest {
        val existing = Account(first, AccountKind.EOA, "Daily", AccountCustody.InKeyStore)
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

        assertEquals(MnemonicImport.Added(keyed(first, "Account 1"), KeyProtection.HARDWARE), result)
    }

    @Test
    fun anUnreadableListOrAFailedSaveSaysTheKeyIsStoredButTheListIsNot() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)

        repository.unreadable = true
        assertEquals(MnemonicImport.ListNotSaved, import(mnemonic, password))

        repository.unreadable = false
        repository.failSaves = true
        assertEquals(MnemonicImport.ListNotSaved, import(mnemonic, password))
        assertEquals(emptyList(), repository.accounts.value)

        // The key is stored, so the same words again finish the job.
        repository.failSaves = false
        assertEquals(
            MnemonicImport.Added(keyed(first, "Account 1"), KeyProtection.HARDWARE),
            import(mnemonic, password),
        )
    }

    @Test
    fun aCancelledCallerDoesNotCutTheImportInHalf() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.Stored(first, KeyProtection.HARDWARE)
        keys.delayMillis = 1_000 // Argon2id takes a while

        val caller = launch { import(mnemonic, password) }
        advanceTimeBy(500)
        caller.cancel() // the screen is left mid-import
        advanceUntilIdle()

        assertEquals(
            listOf(Account(first, AccountKind.EOA, "Account 1", AccountCustody.InKeyStore)),
            repository.accounts.value,
            "the key was stored, so its account is added too",
        )
    }

    @Test
    fun aTooShortFirstPasswordIsRefused() = runTest {
        val mnemonic = Mnemonic("first words")
        keys.results[mnemonic] = KeyImport.PasswordTooShort

        assertEquals(MnemonicImport.PasswordTooShort, import(mnemonic, WalletPassword("short")))
        assertEquals(emptyList(), repository.accounts.value)
    }

    @Test
    fun aWrongPasswordOrAnUnwrittenEnvelopeAddsNoAccount() = runTest {
        val mnemonic = Mnemonic("first words")

        keys.results[mnemonic] = KeyImport.WrongPassword
        assertEquals(MnemonicImport.WrongPassword, import(mnemonic, password))

        keys.results[mnemonic] = KeyImport.NotSaved
        assertEquals(MnemonicImport.KeyNotSaved, import(mnemonic, password))
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
        var delayMillis = 0L

        override suspend fun describe() = KeyStoreDescription.Ready(received.isNotEmpty(), KeyProtection.HARDWARE)

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport {
            received += mnemonic to password
            delay(delayMillis)
            return results.getValue(mnemonic)
        }

        override suspend fun delete(address: Address): KeyDeletion = error("not used by this test")
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

        override suspend fun rename(address: Address, label: String): AccountChange = error("not used by this test")

        override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange {
            if (failSaves) return AccountChange.NotSaved
            if (accounts.value.none { it.address == address }) return AccountChange.NotFound
            accounts.update { list -> list.map { if (it.address == address) it.copy(custody = custody) else it } }
            return AccountChange.Changed
        }

        override suspend fun remove(address: Address): AccountChange = error("not used by this test")
    }
}
