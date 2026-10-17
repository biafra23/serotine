package eth.serotine.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * One value kept in the file at [path] as a versioned JSON document: what the account list and
 * the token settings share. [decode] reads a document of a version up to [formatVersion] (and
 * throws on anything it would not accept: the file is then damaged); [encode] writes the
 * current one, `version` first.
 *
 * Writing: the whole document, into a temporary file of its own that is then moved over the
 * file in one step. A crash during a write leaves the previous one. A power loss in that
 * moment can do worse: kotlinx-io exposes no fsync, so the rename can reach the disk before
 * the data, and the next start then finds a damaged file (below). Each change reads the file
 * again first. One process writes the file: Android runs one, and the desktop host holds a
 * per-user instance lock; were a second one to write it, it could lose a change only within
 * that one change.
 *
 * Reading, which never destroys or hides what is stored:
 * - no file: [empty];
 * - a newer format, or a file that cannot be read: [Read.Unreadable], the file untouched, and
 *   nothing can be changed until it can be read. An unreadable file is read again by every
 *   new subscriber, and every [RETRY_INTERVAL] while one stays subscribed, so it recovers on
 *   its own once the file can be read (a lock released, a permission fixed);
 * - a damaged file: set aside under a name of its own (`<file>.damaged-<random>`, never
 *   replacing an earlier one), then [empty], saying so ([Read.Loaded.damagedSetAside]); if it
 *   cannot be set aside, Unreadable.
 *
 * File work runs on [dispatcher], never the caller's thread. Problems are reported on standard
 * output by type only, naming the file as [what]: an exception message could quote the
 * file's contents.
 */
internal class VersionedJsonFile<T>(
    path: String,
    private val dispatcher: CoroutineDispatcher,
    private val what: String,
    private val formatVersion: Int,
    private val empty: T,
    private val decode: (version: Int, text: String) -> T,
    private val encode: (T) -> String,
) {
    /** What the file holds, or why it cannot be read. */
    sealed interface Read<out T> {
        /** [value]; [damagedSetAside] once a damaged file was set aside and this is what came after. */
        data class Loaded<T>(val value: T, val damagedSetAside: Boolean = false) : Read<T>

        /** It cannot be used: written by a newer version ([newerFormat]), or not readable at all. */
        data class Unreadable(val newerFormat: Boolean) : Read<Nothing>
    }

    /** What a change makes of the value: the [answer], and the value to store, or none to store nothing. */
    class Decision<T, R>(val answer: R, val next: T? = null)

    private val file = Path(path)

    /** Null until the file has been read. */
    private val state = MutableStateFlow<Read<T>?>(null)
    private val lock = Mutex()

    /** The value, re-emitted on each change, or why it cannot be read. Never throws. */
    fun observe(): Flow<Read<T>> = channelFlow {
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
        fun stale() = state.value.let { it == null || it is Read.Unreadable }
        if (stale()) lock.withLock { if (stale()) state.value = load() }
    }

    /**
     * Reads the file again under the lock and lets [decide] make the change on what it holds,
     * then stores the result; [notSaved] when the file cannot be read or written.
     */
    suspend fun <R> change(notSaved: R, decide: (T) -> Decision<T, R>): R = lock.withLock {
        val current = when (val read = load()) {
            is Read.Unreadable -> {
                state.value = read
                return notSaved
            }
            // A notice that the stored value was set aside outlives later reads of the new file.
            is Read.Loaded -> read.copy(damagedSetAside = read.damagedSetAside || setAsideBefore())
        }
        state.value = current
        val decision = decide(current.value)
        val next = decision.next ?: return decision.answer
        // Not cancellable: a write that completes must also be what everyone observes, or a
        // caller cancelled mid-move would leave the file and the observed value apart.
        withContext(NonCancellable) {
            if (write(next)) {
                state.value = current.copy(value = next)
                decision.answer
            } else {
                notSaved
            }
        }
    }

    private fun setAsideBefore(): Boolean = (state.value as? Read.Loaded)?.damagedSetAside == true

    private suspend fun load(): Read<T> = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext Read.Loaded(empty)
        val text = try {
            SystemFileSystem.source(file).buffered().use { it.readString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("could not read the $what at $file (${e::class.simpleName})")
            return@withContext Read.Unreadable(newerFormat = false)
        }
        val version = try {
            LENIENT.decodeFromString(StoredVersion.serializer(), text).version
        } catch (e: Exception) {
            null // not even a JSON object: damaged
        }
        if (version != null && version > formatVersion) return@withContext Read.Unreadable(newerFormat = true)
        val value = try {
            version?.let { decode(it, text) }
        } catch (e: Exception) {
            null
        }
        if (value != null) return@withContext Read.Loaded(value)
        setAside()
    }

    /** Moves a damaged file out of the way, keeping it; Unreadable when that fails. */
    private fun setAside(): Read<T> {
        var aside: Path
        do {
            aside = Path("$file.damaged-${Random.nextLong().toULong().toString(16)}")
        } while (SystemFileSystem.exists(aside))
        return try {
            SystemFileSystem.atomicMove(file, aside)
            report("the $what at $file was damaged; set aside as $aside")
            Read.Loaded(empty, damagedSetAside = true)
        } catch (e: Exception) {
            report("the $what at $file is damaged and could not be set aside (${e::class.simpleName})")
            Read.Unreadable(newerFormat = false)
        }
    }

    private suspend fun write(value: T): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.${Random.nextLong().toULong().toString(16)}.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val text = encode(value)
            SystemFileSystem.sink(temporary).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temporary, file)
            true
        } catch (e: Exception) {
            report("could not save the $what to $file (${e::class.simpleName})")
            try {
                if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
            } catch (cleanup: Exception) {
                // Nothing more to do: a stray temporary file is harmless.
            }
            false
        }
    }

    /** Just the version, read leniently, to tell a newer format from a damaged file; absent, damaged. */
    @Serializable
    private class StoredVersion(val version: Int)

    private companion object {
        val RETRY_INTERVAL = 5.seconds
        val LENIENT = Json { ignoreUnknownKeys = true }

        fun report(what: String) = println("Serotine: $what")
    }
}
