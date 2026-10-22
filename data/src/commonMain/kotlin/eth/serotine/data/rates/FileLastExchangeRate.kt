package eth.serotine.data.rates

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.LastExchangeRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [LastExchangeRate] kept in the file at [path] (a [VersionedJsonFile]): what a home-screen
 * widget shows without starting the light client. A rate in a currency this build does not
 * know, or one that is no rate, reads as none kept; a damaged file is set aside, and one that
 * cannot be read is none kept too, the file left as it is. Public data only.
 */
class FileLastExchangeRate(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LastExchangeRate {

    // Wrapped, never null: the file reads a null value as damaged.
    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "last exchange rate",
        formatVersion = FORMAT_VERSION,
        empty = Kept(null),
        decode = { _, text -> Kept(JSON.decodeFromString(StoredRate.serializer(), text).toSnapshot()) },
        encode = { kept -> JSON.encodeToString(StoredRate.serializer(), StoredRate.of(kept.snapshot)) },
    )

    override suspend fun load(): RateSnapshot? =
        (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value?.snapshot

    override suspend fun save(snapshot: RateSnapshot): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Kept(snapshot)) }

    /** What the file holds: the [snapshot] kept, null for none. */
    private data class Kept(val snapshot: RateSnapshot?)

    /** The file's layout. [version] comes first and is always written; no rate is all null. */
    @Serializable
    private class StoredRate(
        val version: Int,
        val base: String? = null,
        val quote: String? = null,
        val rate: Double? = null,
        val dayAgoRate: Double? = null,
        val updatedAt: Long? = null,
        val readAt: Long? = null,
        val stale: Boolean? = null,
    ) {
        /** The rate it holds, or null when it holds none this build can read. */
        fun toSnapshot(): RateSnapshot? {
            val currency = DisplayCurrency.entries.firstOrNull { it.code == quote } ?: return null
            return runCatching {
                RateSnapshot(PriceId(base!!), currency, rate!!, dayAgoRate, updatedAt!!, readAt!!, stale!!)
            }.getOrNull()
        }

        companion object {
            fun of(snapshot: RateSnapshot?): StoredRate = StoredRate(
                FORMAT_VERSION,
                snapshot?.base?.id,
                snapshot?.quote?.code,
                snapshot?.rate,
                snapshot?.dayAgoRate,
                snapshot?.updatedAt,
                snapshot?.readAt,
                snapshot?.stale,
            )
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json { encodeDefaults = true }
    }
}
