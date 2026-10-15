package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

class FileAccountRepositoryTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-accounts-${Random.nextLong().toULong()}")
    private val file = Path(directory, "accounts.json")

    private val eoa = Account(Address.of("0x" + "aa".repeat(20)), AccountKind.EOA, "Account 1")
    private val safe = Account(Address.of("0x" + "bb".repeat(20)), AccountKind.SAFE, "Treasury")

    private fun TestScope.repository() = FileAccountRepository(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        for (name in listOf("accounts.json", "accounts.json.tmp", "accounts.json.unreadable")) {
            Path(directory, name).let { if (SystemFileSystem.exists(it)) SystemFileSystem.delete(it) }
        }
        if (SystemFileSystem.exists(directory)) SystemFileSystem.delete(directory)
    }

    @Test
    fun startsEmptyWithoutAFile() = runTest {
        assertEquals(emptyList(), repository().observeAccounts().first())
        assertFalse(SystemFileSystem.exists(file), "reading creates nothing")
    }

    @Test
    fun accountsSurviveARestart() = runTest {
        val first = repository()
        assertEquals(AccountAdd.Added, first.add(eoa))
        assertEquals(AccountAdd.Added, first.add(safe))

        assertEquals(listOf(eoa, safe), repository().observeAccounts().first(), "a new instance reads the file")
        assertFalse(SystemFileSystem.exists(Path("$file.tmp")), "the temporary file was moved into place")
    }

    @Test
    fun anAddressAlreadyInTheListIsLeftAlone() = runTest {
        val repository = repository()
        repository.add(eoa)

        assertEquals(AccountAdd.AlreadyPresent, repository.add(eoa.copy(label = "Renamed")))
        assertEquals(listOf(eoa), repository().observeAccounts().first())
    }

    @Test
    fun theFileHoldsAddressesKindsAndLabelsOnly() = runTest {
        repository().add(eoa)

        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertEquals(
            """{"accounts":[{"address":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","kind":"EOA","label":"Account 1"}]}""",
            text,
        )
    }

    @Test
    fun anUnreadableFileIsMovedAsideNotOverwritten() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"accounts":[{"address":"0x12"""") }

        val repository = repository()
        assertEquals(emptyList(), repository.observeAccounts().first())
        assertTrue(SystemFileSystem.exists(Path("$file.unreadable")), "the old file is kept for inspection")

        assertEquals(AccountAdd.Added, repository.add(eoa))
        assertEquals(listOf(eoa), repository().observeAccounts().first())
    }

    @Test
    fun aListThatCannotBeStoredChangesNothing() = runTest {
        // A plain file where the directory should be: nothing can be written below it.
        SystemFileSystem.sink(directory).buffered().use { it.writeString("in the way") }
        val repository = FileAccountRepository(Path(directory, "accounts.json").toString(), StandardTestDispatcher(testScheduler))

        assertEquals(AccountAdd.NotSaved, repository.add(eoa))
        assertEquals(emptyList(), repository.observeAccounts().first())
    }

    @Test
    fun aSubscriberSeesAdditions() = runTest {
        val repository = repository()
        repository.add(eoa)
        val observed = repository.observeAccounts()

        repository.add(safe)

        assertEquals(listOf(eoa, safe), observed.first())
    }
}
