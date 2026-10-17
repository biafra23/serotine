package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileAccountRepositoryTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-accounts-${Random.nextLong().toULong()}")
    private val file = Path(directory, "accounts.json")

    private val eoa =
        Account(Address.of("0x" + "aa".repeat(20)), AccountKind.EOA, "Account 1", AccountCustody.InKeyStore)
    private val safe =
        Account(Address.of("0x" + "bb".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.InKeyStore)
    private val watched =
        Account(Address.of("0x" + "dd".repeat(20)), AccountKind.EOA, "Savings", AccountCustody.Watched)

    private fun TestScope.repository(at: Path = file) =
        FileAccountRepository(at.toString(), StandardTestDispatcher(testScheduler))

    private fun put(path: Path, text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    private fun text(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun setAside(): List<Path> =
        SystemFileSystem.list(directory).filter { it.name.startsWith("accounts.json.damaged-") }

    @AfterTest
    fun cleanUp() {
        fun delete(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                SystemFileSystem.list(path).forEach(::delete)
            }
            SystemFileSystem.delete(path, mustExist = false)
        }
        delete(directory)
    }

    @Test
    fun startsEmptyWithoutAFile() = runTest {
        assertEquals(AccountList.Loaded(emptyList()), repository().observeAccounts().first())
        assertFalse(SystemFileSystem.exists(file), "reading creates nothing")
    }

    @Test
    fun accountsSurviveARestart() = runTest {
        val first = repository()
        assertEquals(AccountAdd.Added, first.add(eoa))
        assertEquals(AccountAdd.Added, first.add(safe))

        assertEquals(AccountList.Loaded(listOf(eoa, safe)), repository().observeAccounts().first())
        assertEquals(listOf("accounts.json"), SystemFileSystem.list(directory).map { it.name }, "no temporary left")
    }

    @Test
    fun theFileIsVersionedAndHoldsAddressesKindsLabelsAndCustodyOnly() = runTest {
        repository().add(eoa)
        repository().add(watched)

        assertEquals(
            """{"version":2,"accounts":[{"address":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
                """"kind":"EOA","label":"Account 1","custody":"key-store"},""" +
                """{"address":"0xdddddddddddddddddddddddddddddddddddddddd",""" +
                """"kind":"EOA","label":"Savings","custody":"watched"}]}""",
            text(file),
        )
        assertEquals(AccountList.Loaded(listOf(eoa, watched)), repository().observeAccounts().first())
    }

    @Test
    fun aFormatOneFileReadsAsAccountsInTheKeyStoreAndIsWrittenAsFormatTwo() = runTest {
        // Format 1 knew no custody: every account in it came from a recovery phrase.
        put(
            file,
            """{"version":1,"accounts":[{"address":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
                """"kind":"EOA","label":"Account 1"}]}""",
        )
        val repository = repository()

        assertEquals(AccountList.Loaded(listOf(eoa)), repository.observeAccounts().first())
        assertEquals(AccountAdd.Added, repository.add(watched))
        assertTrue(text(file).startsWith("""{"version":2,"""))
        assertEquals(AccountList.Loaded(listOf(eoa, watched)), repository().observeAccounts().first())
    }

    @Test
    fun aFormatTwoEntryWithoutCustodyIsDamage() = runTest {
        put(
            file,
            """{"version":2,"accounts":[{"address":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
                """"kind":"EOA","label":"Account 1"}]}""",
        )

        assertEquals(
            AccountList.Loaded(emptyList(), damagedListSetAside = true),
            repository().observeAccounts().first(),
        )
        assertEquals(1, setAside().size)
    }

    @Test
    fun renameAndRemoveChangeOneEntryAndSurviveARestart() = runTest {
        val repository = repository()
        repository.add(eoa)
        repository.add(safe)

        assertEquals(AccountChange.Changed, repository.rename(eoa.address, "Spending"))
        assertEquals(AccountChange.Changed, repository.remove(safe.address))

        assertEquals(AccountList.Loaded(listOf(eoa.copy(label = "Spending"))), repository().observeAccounts().first())
        assertEquals(AccountList.Loaded(listOf(eoa.copy(label = "Spending"))), repository.observeAccounts().first())
    }

    @Test
    fun aWatchedAccountBecomesHeldAndStaysSoAfterARestart() = runTest {
        val repository = repository()
        repository.add(watched)

        assertEquals(AccountChange.Changed, repository.setCustody(watched.address, AccountCustody.InKeyStore))
        assertEquals(AccountChange.NotFound, repository.setCustody(safe.address, AccountCustody.InKeyStore))

        assertEquals(
            AccountList.Loaded(listOf(watched.copy(custody = AccountCustody.InKeyStore))),
            repository().observeAccounts().first(),
        )
    }

    @Test
    fun renameAndRemoveOfAnAddressNotInTheListChangeNothing() = runTest {
        val repository = repository()
        repository.add(eoa)
        val before = text(file)

        assertEquals(AccountChange.NotFound, repository.rename(safe.address, "Treasury"))
        assertEquals(AccountChange.NotFound, repository.remove(safe.address))
        assertEquals(before, text(file))
    }

    @Test
    fun anAddressAlreadyInTheListIsLeftAlone() = runTest {
        val repository = repository()
        repository.add(eoa)

        assertEquals(AccountAdd.AlreadyPresent, repository.add(eoa.copy(label = "Renamed")))
        assertEquals(AccountList.Loaded(listOf(eoa)), repository().observeAccounts().first())
    }

    @Test
    fun aLiveSubscriberSeesAdditions() = runTest {
        val repository = repository()
        val seen = mutableListOf<AccountList>()
        val collecting = launch { repository.observeAccounts().collect { seen += it } }
        runCurrent()

        repository.add(eoa)
        runCurrent()

        assertEquals(listOf<AccountList>(AccountList.Loaded(emptyList()), AccountList.Loaded(listOf(eoa))), seen)
        collecting.cancel()
    }

    @Test
    fun aDamagedFileIsSetAsideIntactAndSaidSo() = runTest {
        put(file, """{"version":1,"accounts":[{"address":"0x12""")
        put(Path("$file.damaged-earlier"), "an earlier one")

        val repository = repository()
        assertEquals(AccountList.Loaded(emptyList(), damagedListSetAside = true), repository.observeAccounts().first())
        val aside = setAside()
        assertEquals(2, aside.size, "the earlier set-aside file is not replaced")
        assertTrue(aside.any { text(it) == """{"version":1,"accounts":[{"address":"0x12""" }, "content kept as it was")

        assertEquals(AccountAdd.Added, repository.add(eoa))
        assertEquals(
            AccountList.Loaded(listOf(eoa), damagedListSetAside = true),
            repository.observeAccounts().first(),
            "the user still hears about the set-aside list",
        )
    }

    @Test
    fun aNewerFormatIsLeftUntouchedAndNothingIsAdded() = runTest {
        val newer = """{"version":3,"accounts":[],"wallets":[]}"""
        put(file, newer)
        val repository = repository()

        assertEquals(AccountList.Unreadable(AccountList.Reason.NEWER_FORMAT), repository.observeAccounts().first())
        assertEquals(AccountAdd.NotSaved, repository.add(eoa))
        assertEquals(AccountChange.NotSaved, repository.remove(eoa.address))
        assertEquals(newer, text(file))
        assertEquals(emptyList(), setAside())
    }

    @Test
    fun aFileThatCannotBeReadIsLeftAloneAndNothingIsAdded() = runTest {
        // A directory where the file should be: it exists, but reading it fails.
        SystemFileSystem.createDirectories(file)
        val repository = repository()

        assertEquals(AccountList.Unreadable(AccountList.Reason.READ_FAILED), repository.observeAccounts().first())
        assertEquals(AccountAdd.NotSaved, repository.add(eoa))
        assertTrue(SystemFileSystem.metadataOrNull(file)?.isDirectory == true)
    }

    @Test
    fun anUnreadableListRecoversOnceTheFileCanBeRead() = runTest {
        SystemFileSystem.createDirectories(file) // unreadable: a directory in the file's place
        val repository = repository()
        val seen = mutableListOf<AccountList>()
        val collecting = launch { repository.observeAccounts().collect { seen += it } }
        runCurrent()
        assertEquals(AccountList.Unreadable(AccountList.Reason.READ_FAILED), seen.last())

        // The obstacle goes away and a readable list appears: the next retry picks it up.
        SystemFileSystem.delete(file)
        repository().add(eoa)
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(AccountList.Loaded(listOf(eoa)), seen.last())
        collecting.cancel()
    }

    @Test
    fun aListThatCannotBeStoredChangesNothing() = runTest {
        // A plain file where the directory should be: nothing can be written below it.
        put(Path(directory, "blocker"), "in the way")
        val repository = repository(Path(Path(directory, "blocker"), "accounts.json"))

        assertEquals(AccountAdd.NotSaved, repository.add(eoa))
        assertEquals(AccountList.Loaded(emptyList()), repository.observeAccounts().first())
    }

    @Test
    fun anAddReadsTheFileAgainSoAnotherWritersAccountIsKept() = runTest {
        val mine = repository()
        mine.add(eoa)
        // Another process adds an account behind this instance's back.
        repository().add(safe)

        mine.add(Account(Address.of("0x" + "cc".repeat(20)), AccountKind.EOA, "Account 3", AccountCustody.InKeyStore))

        val stored = (repository().observeAccounts().first() as AccountList.Loaded).accounts
        assertEquals(listOf("Account 1", "Treasury", "Account 3"), stored.map { it.label })
    }
}
