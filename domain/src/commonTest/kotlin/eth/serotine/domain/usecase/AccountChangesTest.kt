package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ObserveAccount], [RenameAccount] and [RemoveAccount] over fake ports. */
class AccountChangesTest {

    private val keyed = Account(Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Daily", AccountCustody.InKeyStore)
    private val watched =
        Account(Address.of("0x" + "22".repeat(20)), AccountKind.EOA, "Savings", AccountCustody.Watched)
    private val stranger = Address.of("0x" + "33".repeat(20))

    private val repository = ListAccountRepository(keyed, watched)
    private val keys = RecordingKeyStore(listOf(keyed.address))

    @Test
    fun observeAccountFollowsOneEntryUntilItIsGone() = runTest {
        val seen = mutableListOf<AccountLookup>()
        val collecting = launch { ObserveAccount(repository)(keyed.address).take(3).toList(seen) }
        testScheduler.runCurrent()
        repository.accounts.value = listOf(keyed.copy(label = "Spending"), watched)
        testScheduler.runCurrent()
        // A change to another entry is not a change to this one.
        repository.accounts.value = listOf(keyed.copy(label = "Spending"))
        testScheduler.runCurrent()
        repository.accounts.value = emptyList()
        collecting.join()

        assertEquals(
            listOf(
                AccountLookup.Found(keyed),
                AccountLookup.Found(keyed.copy(label = "Spending")),
                AccountLookup.NotInList,
            ),
            seen,
        )
    }

    @Test
    fun observeAccountSaysWhenTheListCannotBeRead() = runTest {
        repository.unreadable = true

        assertEquals(AccountLookup.ListUnavailable, ObserveAccount(repository)(keyed.address).first())
    }

    @Test
    fun renameTrimsTheNameAndRefusesABlankOrOverlongOne() = runTest {
        val rename = RenameAccount(repository)

        assertEquals(AccountRename.Renamed, rename(keyed.address, "  Spending  "))
        assertEquals("Spending", repository.accounts.value.first().label)
        assertEquals(AccountRename.Blank, rename(keyed.address, "   "))
        assertEquals(AccountRename.TooLong, rename(keyed.address, "x".repeat(RenameAccount.MAX_LABEL_LENGTH + 1)))
        assertEquals(AccountRename.Renamed, rename(keyed.address, "x".repeat(RenameAccount.MAX_LABEL_LENGTH)))
        assertEquals(AccountRename.NotFound, rename(stranger, "Anything"))
    }

    @Test
    fun aRenameThatCannotBeSavedSaysSo() = runTest {
        repository.failSaves = true

        assertEquals(AccountRename.NotSaved, RenameAccount(repository)(keyed.address, "Spending"))
        assertEquals("Daily", repository.accounts.value.first().label)
    }

    @Test
    fun removingAnAccountHeldInTheKeyStoreDeletesItsKeyAfterTheEntry() = runTest {
        assertEquals(AccountRemoval.Removed, RemoveAccount(repository, keys, WalletChanges())(keyed.address))

        assertEquals(listOf(watched), repository.accounts.value)
        assertEquals(listOf(keyed.address), keys.deleted)
    }

    @Test
    fun removingAWatchedAccountLeavesTheOtherKeysAlone() = runTest {
        assertEquals(AccountRemoval.Removed, RemoveAccount(repository, keys, WalletChanges())(watched.address))

        assertEquals(listOf(keyed), repository.accounts.value)
        assertEquals(emptyList(), keys.deleted, "a watched account has no key to delete")
    }

    @Test
    fun anImportAndARemovalOfTheSameAddressNeverInterleave() = runTest {
        val changes = WalletChanges()
        keys.importing = watched.address
        keys.importDelayMillis = 1_000
        val import = async { ImportMnemonicAccount(keys, repository, changes)(Mnemonic("words"), WalletPassword("pw")) }
        runCurrent() // the import holds the wallet while it stores the key
        val removal = async { RemoveAccount(repository, keys, changes)(watched.address) }
        advanceUntilIdle()

        assertEquals(MnemonicImport.KeyAdded(watched.copy(custody = AccountCustody.InKeyStore)), import.await())
        // The removal ran after it: it found the entry held, and its key went with it.
        assertEquals(AccountRemoval.Removed, removal.await())
        assertEquals(listOf(keyed), repository.accounts.value)
        assertEquals(setOf(keyed.address), keys.stored, "no key outlives its account, none is lost under one")
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedWithTheEntryGone() = runTest {
        keys.failDeletes = true

        assertEquals(AccountRemoval.RemovedKeyKept, RemoveAccount(repository, keys, WalletChanges())(keyed.address))
        assertEquals(listOf(watched), repository.accounts.value)
    }

    @Test
    fun anEntryThatCannotBeRemovedKeepsItsKey() = runTest {
        repository.failSaves = true

        assertEquals(AccountRemoval.NotSaved, RemoveAccount(repository, keys, WalletChanges())(keyed.address))
        assertEquals(emptyList(), keys.deleted, "the key never goes before the entry")
    }

    @Test
    fun removingAnAddressNotInTheWalletChangesNothing() = runTest {
        assertEquals(AccountRemoval.NotFound, RemoveAccount(repository, keys, WalletChanges())(stranger))
        assertEquals(emptyList(), keys.deleted)

        repository.unreadable = true
        assertEquals(AccountRemoval.NotSaved, RemoveAccount(repository, keys, WalletChanges())(keyed.address))
    }
}
