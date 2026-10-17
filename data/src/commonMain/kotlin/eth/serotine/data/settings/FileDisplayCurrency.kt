package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.DisplayCurrencySetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [DisplayCurrencySetting] kept in the file at [path] (a [VersionedJsonFile]). A currency this
 * build does not know, written by a newer one, reads as no choice; a damaged file is set
 * aside, saying so until another choice is made ([CurrencyChoice.Chosen.setAside]), and one
 * that cannot be read is [CurrencyChoice.Unreadable].
 */
class FileDisplayCurrency(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DisplayCurrencySetting {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "display currency",
        formatVersion = FORMAT_VERSION,
        empty = Saved(currency = null),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredChoice.serializer(), text)
            Saved(DisplayCurrency.entries.firstOrNull { it.code == stored.currency })
        },
        encode = { saved ->
            JSON.encodeToString(StoredChoice.serializer(), StoredChoice(FORMAT_VERSION, saved.currency?.code))
        },
    )

    override fun observe(): Flow<CurrencyChoice> = file.observe().map { read ->
        when (read) {
            // Said until a choice is made again: then nothing is missing any more.
            is VersionedJsonFile.Read.Loaded -> {
                val chosen = read.value.currency
                CurrencyChoice.Chosen(chosen, setAside = read.damagedSetAside && chosen == null)
            }
            is VersionedJsonFile.Read.Unreadable -> CurrencyChoice.Unreadable
        }
    }

    override suspend fun choose(currency: DisplayCurrency): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(currency)) }

    /** What the file holds: the [currency] chosen, null for none (or one this build does not know). */
    private data class Saved(val currency: DisplayCurrency?)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredChoice(val version: Int, val currency: String?)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
