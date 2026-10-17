package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        assertEquals(AccountRemoval.Removed, RemoveAccount(repository, keys)(keyed.address))

        assertEquals(listOf(watched), repository.accounts.value)
        assertEquals(listOf(keyed.address), keys.deleted)
    }

    @Test
    fun removingAWatchedAccountLeavesTheOtherKeysAlone() = runTest {
        assertEquals(AccountRemoval.Removed, RemoveAccount(repository, keys)(watched.address))

        assertEquals(listOf(keyed), repository.accounts.value)
        assertEquals(setOf(keyed.address), keys.stored, "the key store is asked, and holds nothing for it")
    }

    @Test
    fun aKeyImportedForAWatchedEntryWhileItIsRemovedGoesWithIt() = runTest {
        // The removal read the entry as watched; the key arrived before the entry went.
        keys.stored += watched.address

        assertEquals(AccountRemoval.Removed, RemoveAccount(repository, keys)(watched.address))
        assertEquals(listOf(watched.address), keys.deleted)
        assertEquals(emptySet(), keys.stored - keyed.address, "no key outlives its account")
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedWithTheEntryGone() = runTest {
        keys.failDeletes = true

        assertEquals(AccountRemoval.RemovedKeyKept, RemoveAccount(repository, keys)(keyed.address))
        assertEquals(listOf(watched), repository.accounts.value)
    }

    @Test
    fun anEntryThatCannotBeRemovedKeepsItsKey() = runTest {
        repository.failSaves = true

        assertEquals(AccountRemoval.NotSaved, RemoveAccount(repository, keys)(keyed.address))
        assertEquals(emptyList(), keys.deleted, "the key never goes before the entry")
    }

    @Test
    fun removingAnAddressNotInTheWalletChangesNothing() = runTest {
        assertEquals(AccountRemoval.NotFound, RemoveAccount(repository, keys)(stranger))
        assertEquals(emptyList(), keys.deleted)

        repository.unreadable = true
        assertEquals(AccountRemoval.NotSaved, RemoveAccount(repository, keys)(keyed.address))
    }
}
