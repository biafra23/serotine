package eth.serotine.data.history

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.HistoryStartList
import eth.serotine.domain.repository.HistoryStarts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [HistoryStarts] kept in the file at [path] (a [VersionedJsonFile]), so a start survives a
 * restart: addresses and blocks or times, public data only. A file that cannot be used is
 * handled as [VersionedJsonFile] says: a newer format or one that cannot be read is
 * [HistoryStartList.Unreadable] and nothing is written over it; a damaged one is set aside, and
 * the starts are noted again from what comes after. An entry that is neither a time nor a block
 * damages the file.
 */
class FileHistoryStarts(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryStarts {

    private val file = VersionedJsonFile<Map<Address, HistoryStart>>(
        path,
        dispatcher,
        what = "history starts",
        formatVersion = FORMAT_VERSION,
        empty = emptyMap(),
        decode = { _, text ->
            JSON.decodeFromString(StoredStarts.serializer(), text).starts.associate { it.toStart() }
        },
        encode = { starts ->
            val stored = StoredStarts(FORMAT_VERSION, starts.map { (address, start) -> StoredStart(address, start) })
            JSON.encodeToString(StoredStarts.serializer(), stored)
        },
    )

    override fun observe(): Flow<HistoryStartList> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> HistoryStartList.Loaded(read.value)
            is VersionedJsonFile.Read.Unreadable -> HistoryStartList.Unreadable
        }
    }

    override suspend fun note(address: Address, atSeconds: Long): Boolean = file.change(notSaved = false) { starts ->
        Decision(true, starts + (address to HistoryStart.Noted(atSeconds)))
    }

    override suspend fun fix(address: Address, block: Long): Boolean = file.change(notSaved = false) { starts ->
        if (starts[address] is HistoryStart.Fixed) {
            Decision(false)
        } else {
            Decision(true, starts + (address to HistoryStart.Fixed(block)))
        }
    }

    override suspend fun forget(address: Address, start: HistoryStart): Boolean =
        file.change(notSaved = false) { starts ->
            if (starts[address] != start) Decision(true) else Decision(true, starts - address)
        }

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredStarts(val version: Int, val starts: List<StoredStart>)

    /** One start: [address] with its [block] once fixed, or the time it was [addedAt] (unix seconds) before. */
    @Serializable
    private class StoredStart(val address: String, val block: Long? = null, val addedAt: Long? = null) {
        constructor(address: Address, start: HistoryStart) : this(
            address.hex,
            block = (start as? HistoryStart.Fixed)?.block,
            addedAt = (start as? HistoryStart.Noted)?.atSeconds,
        )

        /** Throws on anything but exactly one of the two: the whole file is then damaged. */
        fun toStart(): Pair<Address, HistoryStart> = Address.of(address) to when {
            block != null && addedAt == null -> HistoryStart.Fixed(block)
            addedAt != null && block == null -> HistoryStart.Noted(addedAt)
            else -> error("a start that is neither a block nor a time")
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
