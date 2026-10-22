package eth.serotine.data.rates

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.LastExchangeRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [LastExchangeRate] kept in the file at [path] (a [VersionedJsonFile]), one rate per network:
 * what a home-screen widget shows without starting the light client. Format 2 keeps each
 * network's by its chain id; the one rate of a format-1 file was Sepolia's, the only one a
 * widget could show then (docs/networks.md, *The widget*). A rate of a network or in a currency
 * this build does not know, or one that is no rate, reads as none kept, and the file is kept aside
 * before the next save rewrites it, so a newer build's rates are not lost; a damaged file is set
 * aside, and one that cannot be read is none kept too, the file left as it is. Public data only.
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
        empty = Kept(emptyMap()),
        decode = { version, text ->
            if (version < 2) {
                val one = JSON.decodeFromString(StoredRate.serializer(), text).toSnapshot()
                Kept(listOfNotNull(one?.let { Network.SEPOLIA to it }).toMap())
            } else {
                val stored = JSON.decodeFromString(StoredRates.serializer(), text)
                Kept(
                    stored.rates.mapNotNull { rate ->
                        val network = rate.chain?.let(Network::ofChainId) ?: return@mapNotNull null
                        rate.toSnapshot()?.let { network to it }
                    }.toMap(),
                )
            }
        },
        // A rate this build cannot read (a network or a currency it does not know): the file is kept
        // aside before the next save rewrites it, as every file is that passes entries over.
        incomplete = { text, kept ->
            val stored = LENIENT.decodeFromString(StoredRateFile.serializer(), text)
            stored.version >= 2 && stored.rates.size > kept.rates.size
        },
        encode = { kept ->
            val stored = StoredRates(
                FORMAT_VERSION,
                kept.rates.map { (network, snapshot) -> StoredRate.of(network, snapshot) },
            )
            JSON.encodeToString(StoredRates.serializer(), stored)
        },
    )

    override suspend fun load(network: Network): RateSnapshot? =
        (file.observe().first() as? VersionedJsonFile.Read.Loaded)?.value?.rates?.get(network)

    override suspend fun save(network: Network, snapshot: RateSnapshot): Boolean =
        file.change(notSaved = false) { kept -> Decision(true, next = Kept(kept.rates + (network to snapshot))) }

    /** What the file holds: each network's rate kept. */
    private data class Kept(val rates: Map<Network, RateSnapshot>)

    /** The file's layout from format 2. [version] comes first and is always written. */
    @Serializable
    private class StoredRates(val version: Int, val rates: List<StoredRate>)

    /** Any format's file, read leniently: its [version], and from format 2 its [rates]. */
    @Serializable
    private class StoredRateFile(val version: Int, val rates: List<JsonElement> = emptyList())

    /**
     * One rate: format 1's whole file, with its [version]; from format 2 a network's, the network
     * of [chain]. No rate is all null.
     */
    @Serializable
    private class StoredRate(
        val version: Int? = null,
        val chain: Long? = null,
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
            fun of(network: Network, snapshot: RateSnapshot?): StoredRate = StoredRate(
                null,
                network.chainId,
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
        /** 2 since networks: one rate per network, by its chain id. */
        const val FORMAT_VERSION = 2
        val JSON = Json { encodeDefaults = true }
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
