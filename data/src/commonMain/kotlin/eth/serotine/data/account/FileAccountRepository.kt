package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AccountRepository] kept in the file at [path], so the accounts survive a restart: addresses, kinds
 * and labels, never key material. The list is one JSON document, rewritten whole on every
 * change into a temporary file that is then moved over [file] in one step: a crash during
 * a write leaves the previous list, never part of one. [path] is a plain string, so no
 * file-system library type leaves this module. (The platforms' file APIs here
 * offer no fsync, so a power loss in the same moment can still lose the newest change.)
 *
 * The file is read once, on [dispatcher], when the accounts are first observed or added.
 * A file that cannot be parsed is moved aside to `<file>.unreadable` and the list starts
 * empty, said on standard output: re-importing an account recovers it, while a silent overwrite would
 * not leave the old file for anyone to look at.
 */
class FileAccountRepository(
    path: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {

    private val file = Path(path)

    /** Null until the file has been read. */
    private val accounts = MutableStateFlow<List<Account>?>(null)
    private val lock = Mutex()

    override fun observeAccounts(): Flow<List<Account>> = flow {
        loaded()
        emitAll(accounts.filterNotNull())
    }

    override suspend fun add(account: Account): AccountAdd = lock.withLock {
        val current = loadedLocked()
        if (current.any { it.address == account.address }) return AccountAdd.AlreadyPresent
        val next = current + account
        // The file first, then the list everyone observes: a failed write changes nothing.
        if (!write(next)) return AccountAdd.NotSaved
        accounts.value = next
        AccountAdd.Added
    }

    private suspend fun loaded(): List<Account> = accounts.value ?: lock.withLock { loadedLocked() }

    private suspend fun loadedLocked(): List<Account> =
        accounts.value ?: read().distinctBy { it.address }.also { accounts.value = it }

    private suspend fun read(): List<Account> = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext emptyList()
        try {
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            JSON.decodeFromString<StoredAccounts>(text).accounts.map { it.toAccount() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val aside = Path("$file.unreadable")
            report("the account list in $file is unreadable ($e); moved to $aside, starting empty")
            try {
                SystemFileSystem.atomicMove(file, aside)
            } catch (moveFailure: Exception) {
                report("could not move it aside either ($moveFailure)")
            }
            emptyList()
        }
    }

    private suspend fun write(accounts: List<Account>): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val text = JSON.encodeToString(StoredAccounts.serializer(), StoredAccounts(accounts.map(::StoredAccount)))
            SystemFileSystem.sink(temporary).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temporary, file)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not save the account list to $file ($e)")
            false
        }
    }

    /** The file's layout. [version] is there so a later layout can tell an old file apart. */
    @Serializable
    private class StoredAccounts(val accounts: List<StoredAccount>, val version: Int = 1)

    @Serializable
    private class StoredAccount(val address: String, val kind: String, val label: String) {
        constructor(account: Account) : this(account.address.hex, account.kind.name, account.label)

        /** Throws on anything [Account] would not accept: the whole file is then unreadable. */
        fun toAccount(): Account = Account(Address.of(address), AccountKind.valueOf(kind), label)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = false }

        fun report(what: String) = println("Serotine: $what")
    }
}
