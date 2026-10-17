package eth.serotine.data.account

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * [AccountRepository] kept in the file at [path], so the accounts survive a restart:
 * addresses, kinds, labels and how each is held, never key material. [path] is a plain
 * string, so no file-system library type leaves this module.
 *
 * Writing: the whole list, as one versioned JSON document, into a temporary file of its own
 * that is then moved over the file in one step. A crash during a write leaves the previous
 * list. A power loss in that moment can do worse: kotlinx-io exposes no fsync, so the
 * rename can reach the disk before the data, and the next start then finds a damaged file
 * (below). Each change reads the file again first. One process writes the file: Android
 * runs one, and the desktop host holds a per-user instance lock; were a second one to write
 * it, it could lose a change only within that one change.
 *
 * Format 2 records each account's custody. A format-1 file, written before watched accounts
 * existed, held only accounts added from a recovery phrase, so its entries read as
 * [AccountCustody.InKeyStore]; the next change writes the file as format 2.
 *
 * Reading, which never destroys or hides the stored list:
 * - no file: an empty list;
 * - a newer format, or a file that cannot be read: [AccountList.Unreadable], the file
 *   untouched, and nothing can be added until it can be read. An unreadable list is read
 *   again by every new subscriber, and every [RETRY_INTERVAL] while one stays subscribed,
 *   so it recovers on its own once the file can be read (a lock released, a permission
 *   fixed);
 * - a damaged file: set aside under a name of its own (`<file>.damaged-<random>`, never
 *   replacing an earlier one), then an empty list that says so
 *   ([AccountList.Loaded.damagedListSetAside]); if it cannot be set aside, Unreadable.
 *
 * File work runs on [dispatcher], never the caller's thread. Problems are reported on
 * standard output by type only: an exception message could quote the file's addresses.
 */
class FileAccountRepository(
    path: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {

    private val file = Path(path)

    /** Null until the file has been read. */
    private val state = MutableStateFlow<AccountList?>(null)
    private val lock = Mutex()

    override fun observeAccounts(): Flow<AccountList> = channelFlow {
        refreshIfNeeded()
        launch {
            while (true) {
                delay(RETRY_INTERVAL)
                refreshIfNeeded()
            }
        }
        state.filterNotNull().collect { send(it) }
    }

    /** Reads the file when it has not been read yet, or when the last read found it unreadable. */
    private suspend fun refreshIfNeeded() {
        fun stale() = state.value.let { it == null || it is AccountList.Unreadable }
        if (stale()) lock.withLock { if (stale()) state.value = load() }
    }

    override suspend fun add(account: Account): AccountAdd = change(AccountAdd.NotSaved) { accounts ->
        if (accounts.any { it.address == account.address }) {
            Decision(AccountAdd.AlreadyPresent)
        } else {
            Decision(AccountAdd.Added, accounts + account)
        }
    }

    override suspend fun rename(address: Address, label: String): AccountChange =
        updateEntry(address) { it.copy(label = label) }

    override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange =
        updateEntry(address) { it.copy(custody = custody) }

    /** Replaces the entry under [address] with [update] of it, in place; NotFound when there is none. */
    private suspend fun updateEntry(address: Address, update: (Account) -> Account): AccountChange =
        change(AccountChange.NotSaved) { accounts ->
            val index = accounts.indexOfFirst { it.address == address }
            if (index < 0) {
                Decision(AccountChange.NotFound)
            } else {
                Decision(AccountChange.Changed, accounts.toMutableList().apply { set(index, update(get(index))) })
            }
        }

    override suspend fun remove(address: Address): AccountChange = change(AccountChange.NotSaved) { accounts ->
        if (accounts.none { it.address == address }) {
            Decision(AccountChange.NotFound)
        } else {
            Decision(AccountChange.Changed, accounts.filterNot { it.address == address })
        }
    }

    /** What a change makes of the list: the [answer], and the list to store, or none to store nothing. */
    private class Decision<R>(val answer: R, val next: List<Account>? = null)

    /**
     * Reads the file again under the lock and lets [decide] make the change on what it holds,
     * then stores the result; [notSaved] when the file cannot be read or written.
     */
    private suspend fun <R> change(notSaved: R, decide: (List<Account>) -> Decision<R>): R = lock.withLock {
        val current = when (val read = load()) {
            is AccountList.Unreadable -> {
                state.value = read
                return notSaved
            }
            // A notice that the stored list was set aside outlives later reads of the new file.
            is AccountList.Loaded -> read.copy(damagedListSetAside = read.damagedListSetAside || setAsideBefore())
        }
        state.value = current
        val decision = decide(current.accounts)
        val next = decision.next ?: return decision.answer
        // Not cancellable: a write that completes must also be what everyone observes, or a
        // caller cancelled mid-move would leave the file and the observed list apart.
        withContext(NonCancellable) {
            if (write(next)) {
                state.value = current.copy(accounts = next)
                decision.answer
            } else {
                notSaved
            }
        }
    }

    private fun setAsideBefore(): Boolean = (state.value as? AccountList.Loaded)?.damagedListSetAside == true

    private suspend fun load(): AccountList = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext AccountList.Loaded(emptyList())
        val text = try {
            SystemFileSystem.source(file).buffered().use { it.readString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not read the account list at $file (${e::class.simpleName})")
            return@withContext AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        }
        val version = try {
            LENIENT.decodeFromString(StoredVersion.serializer(), text).version
        } catch (e: Exception) {
            null // not even a JSON object: damaged
        }
        if (version != null && version > FORMAT_VERSION) {
            return@withContext AccountList.Unreadable(AccountList.Reason.NEWER_FORMAT)
        }
        val accounts = try {
            val stored = STRICT.decodeFromString(StoredAccounts.serializer(), text)
            stored.accounts.map { it.toAccount(stored.version) }
        } catch (e: Exception) {
            null
        }
        if (accounts != null) return@withContext AccountList.Loaded(accounts.distinctBy { it.address })
        setAside()
    }

    /** Moves a damaged file out of the way, keeping it; Unreadable when that fails. */
    private fun setAside(): AccountList {
        var aside: Path
        do {
            aside = Path("$file.damaged-${Random.nextLong().toULong().toString(16)}")
        } while (SystemFileSystem.exists(aside))
        return try {
            SystemFileSystem.atomicMove(file, aside)
            report("the account list at $file was damaged; set aside as $aside")
            AccountList.Loaded(emptyList(), damagedListSetAside = true)
        } catch (e: Exception) {
            report("the account list at $file is damaged and could not be set aside (${e::class.simpleName})")
            AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        }
    }

    private suspend fun write(accounts: List<Account>): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.${Random.nextLong().toULong().toString(16)}.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val text = STRICT.encodeToString(
                StoredAccounts.serializer(),
                StoredAccounts(FORMAT_VERSION, accounts.map(::StoredAccount)),
            )
            SystemFileSystem.sink(temporary).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temporary, file)
            true
        } catch (e: Exception) {
            report("could not save the account list to $file (${e::class.simpleName})")
            try {
                if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
            } catch (cleanup: Exception) {
                // Nothing more to do: a stray temporary file is harmless.
            }
            false
        }
    }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredAccounts(val version: Int, val accounts: List<StoredAccount>)

    @Serializable
    private class StoredAccount(
        val address: String,
        val kind: String,
        val label: String,
        /** [WATCHED] or [IN_KEY_STORE]; absent in format 1, whose accounts all came from a recovery phrase. */
        val custody: String? = null,
    ) {
        constructor(account: Account) : this(
            account.address.hex,
            account.kind.name,
            account.label,
            when (account.custody) {
                AccountCustody.Watched -> WATCHED
                AccountCustody.InKeyStore -> IN_KEY_STORE
            },
        )

        /** Throws on anything [Account] would not accept, in a file of [version]: the whole file is then damaged. */
        fun toAccount(version: Int): Account = Account(
            Address.of(address),
            AccountKind.valueOf(kind),
            label,
            when {
                version == 1 && custody == null -> AccountCustody.InKeyStore
                custody == WATCHED -> AccountCustody.Watched
                custody == IN_KEY_STORE -> AccountCustody.InKeyStore
                else -> error("an account without a known custody")
            },
        )
    }

    /** Just the version, read leniently, to tell a newer format from a damaged file. */
    @Serializable
    private class StoredVersion(val version: Int = FORMAT_VERSION)

    private companion object {
        /** 2 since watched accounts: each entry records its custody. */
        const val FORMAT_VERSION = 2
        const val WATCHED = "watched"
        const val IN_KEY_STORE = "key-store"
        val RETRY_INTERVAL = 5.seconds
        val STRICT = Json
        val LENIENT = Json { ignoreUnknownKeys = true }

        fun report(what: String) = println("Serotine: $what")
    }
}
