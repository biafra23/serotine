package eth.serotine.data.upgrade

import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

/**
 * [KnownUpgradeStore] kept in the file at [path], for the app version [appVersion]: a record
 * another version wrote reads as none and is replaced by the next save, since updating the
 * app is what the record asks for, and an updated app hears from its own engine whether its
 * build supports the upgrade. Peer reports only (times, fork ids, what the user was told):
 * nothing in it is key material or chain state.
 *
 * Not the account list: a record that cannot be read, or a damaged one, reads as none rather
 * than being kept aside, as losing it costs at most a notice shown again. Written whole, to a
 * temporary file moved over the old one, on [dispatcher]. One process writes it (Android runs
 * one, the desktop holds its instance lock).
 */
class FileKnownUpgradeStore(
    path: String,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KnownUpgradeStore {

    private val file = Path(path)

    /** Null until the file has been read. */
    private val state = MutableStateFlow<Read?>(null)
    private val lock = Mutex()

    /** What the file holds: [record], or none. */
    private data class Read(val record: KnownUpgrade?)

    override fun observe(): Flow<KnownUpgrade?> = state
        .onStart { if (state.value == null) lock.withLock { if (state.value == null) state.value = Read(load()) } }
        .filterNotNull()
        .map { it.record }

    override suspend fun save(record: KnownUpgrade): Boolean = lock.withLock {
        // Not cancellable: a written record must also be what everyone observes.
        withContext(NonCancellable) {
            write(record).also { if (it) state.value = Read(record) }
        }
    }

    private suspend fun load(): KnownUpgrade? = withContext(dispatcher) {
        if (!SystemFileSystem.exists(file)) return@withContext null
        try {
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            val stored = JSON.decodeFromString(StoredRecord.serializer(), text)
            if (stored.version != FORMAT_VERSION || stored.appVersion != appVersion) null else stored.toKnownUpgrade()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("the upgrade record at $file cannot be used (${e::class.simpleName}); it is read as none")
            null
        }
    }

    private suspend fun write(record: KnownUpgrade): Boolean = withContext(dispatcher) {
        val temporary = Path("$file.${Random.nextLong().toULong().toString(16)}.tmp")
        try {
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            val text = JSON.encodeToString(StoredRecord.serializer(), StoredRecord(record, appVersion))
            SystemFileSystem.sink(temporary).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temporary, file)
            true
        } catch (e: Exception) {
            report("could not save the upgrade record to $file (${e::class.simpleName})")
            try {
                if (SystemFileSystem.exists(temporary)) SystemFileSystem.delete(temporary)
            } catch (cleanup: Exception) {
                // Nothing more to do: a stray temporary file is harmless.
            }
            false
        }
    }

    /** The file's layout. [version] is the format's, [appVersion] the app's that wrote it. */
    @Serializable
    private class StoredRecord(
        val version: Int,
        val appVersion: String,
        val phase: String,
        val activationTime: Long? = null,
        val forkId: String? = null,
        val reportingNetworks: Int,
        val shown: List<String>,
    ) {
        constructor(record: KnownUpgrade, appVersion: String) : this(
            version = FORMAT_VERSION,
            appVersion = appVersion,
            phase = record.upgrade.phase.name,
            activationTime = record.upgrade.activationTime,
            forkId = record.upgrade.forkId,
            reportingNetworks = record.upgrade.reportingNetworks,
            shown = record.shown.map { it.name }.sorted(),
        )

        /** Throws on anything the domain would not accept: the file is then read as none. */
        fun toKnownUpgrade() = KnownUpgrade(
            NetworkUpgrade(NetworkUpgrade.Phase.valueOf(phase), activationTime, forkId, reportingNetworks),
            shown.mapTo(HashSet()) { UpgradeNotice.Kind.valueOf(it) },
        )
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        fun report(what: String) = println("Serotine: $what")
    }
}
