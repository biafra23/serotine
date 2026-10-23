package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.VenueChoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [SwapVenueSetting] kept in the file at [path] (a [VersionedJsonFile]), as the display currency
 * is kept: the venue the user last chose on Swap, compose. A venue this build does not know,
 * written by a newer one, reads as no choice; a damaged file is set aside, saying so until another
 * choice is made ([VenueChoice.Chosen.setAside]), and one that cannot be read is
 * [VenueChoice.Unreadable].
 */
class FileSwapVenue(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SwapVenueSetting {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "swap venue",
        formatVersion = FORMAT_VERSION,
        empty = Saved(venue = null),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredChoice.serializer(), text)
            Saved(SwapVenue.entries.firstOrNull { it.code == stored.venue })
        },
        encode = { saved ->
            JSON.encodeToString(StoredChoice.serializer(), StoredChoice(FORMAT_VERSION, saved.venue?.code))
        },
    )

    override fun observe(): Flow<VenueChoice> = file.observe().map { read ->
        when (read) {
            is VersionedJsonFile.Read.Loaded -> {
                val chosen = read.value.venue
                VenueChoice.Chosen(chosen, setAside = read.damagedSetAside && chosen == null)
            }
            is VersionedJsonFile.Read.Unreadable -> VenueChoice.Unreadable
        }
    }

    override suspend fun choose(venue: SwapVenue): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(venue)) }

    /** What the file holds: the [venue] chosen, null for none (or one this build does not know). */
    private data class Saved(val venue: SwapVenue?)

    /** The file's layout. [version] comes first and is always written. */
    @Serializable
    private class StoredChoice(val version: Int, val venue: String?)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json

        /** How a venue is written: a name of its own, not the enum's, so a rename changes no file. */
        val SwapVenue.code: String
            get() = when (this) {
                SwapVenue.UNISWAP -> "uniswap"
                SwapVenue.COW -> "cow"
            }
    }
}
