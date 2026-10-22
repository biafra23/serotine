package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.repository.DebugScreenSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [DebugScreenSetting] kept in the file at [path] (a [VersionedJsonFile]). No file, a damaged
 * one (set aside) and one that cannot be read are all off: the tab is a developer's tool, and
 * the user turns it on again in Settings.
 */
class FileDebugScreen(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DebugScreenSetting {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "debug screen",
        formatVersion = FORMAT_VERSION,
        empty = Saved(enabled = false),
        decode = { _, text -> Saved(JSON.decodeFromString(StoredSwitch.serializer(), text).enabled) },
        encode = { saved ->
            JSON.encodeToString(StoredSwitch.serializer(), StoredSwitch(FORMAT_VERSION, saved.enabled))
        },
    )

    override fun observe(): Flow<Boolean> = file.observe().map { read ->
        (read as? VersionedJsonFile.Read.Loaded)?.value?.enabled == true
    }

    override suspend fun set(enabled: Boolean): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(enabled)) }

    /** What the file holds; wrapped, since the file reads a null value as damaged. */
    private data class Saved(val enabled: Boolean)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredSwitch(val version: Int, val enabled: Boolean)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
