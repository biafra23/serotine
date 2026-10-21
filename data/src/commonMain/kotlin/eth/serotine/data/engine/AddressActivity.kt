package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.concurrent.Volatile

/**
 * What [AddressActivity] needs of the engine: its address-activity follower
 * (`io.myotis.api.ChainHandle.setActivityWatch` and `addressActivityJson`, Myotis #691), JSON in
 * and out as the engine speaks it. Neither call does I/O.
 */
internal interface ActivityChain {

    /** Installs the watch list change [configJson]; true once the engine took it. */
    fun setActivityWatch(configJson: String): Boolean

    /**
     * The activity of the watched addresses after [afterBlock] as the engine's JSON, an
     * `{"error":…}` object, or null: no engine, or one without the follower.
     */
    fun addressActivity(afterBlock: Long): String?
}

/**
 * Whether a proof of an address's state still stands at a later verified head, from the
 * engine's address-activity report: true only when every block from the proof's own through
 * the head was checked for that address and none touched it. Anything the engine did not check
 * — a block before it followed, a gap, a report it could not give — is "not known unchanged",
 * and the proof is made again, as without this.
 *
 * An address is watched the first time it is asked about, so the answer for it is false until
 * the engine's next report lists it. A report is asked for once per verified head, however many
 * readings ask; one that no longer lists an address (the engine restarted) watches it again.
 */
internal class AddressActivity(private val chain: ActivityChain?) {

    /** What a fact about an address changes with. */
    enum class Kind(val json: String) {
        /** The account itself: its balance, nonce or code (the engine's block-access-list coverage). */
        ACCOUNT("account"),

        /** A log naming the address, such as a token `Transfer` (the engine's logs-bloom coverage). */
        LOG("log"),
    }

    /** The last report read, or null before the first. */
    @Volatile
    private var last: Report? = null

    /**
     * Whether the [kind] of fact about [address] proven at block [provenAt] is known not to have
     * changed by the verified head [head]. Never blocks on I/O.
     */
    fun unchangedSince(address: Address, kind: Kind, provenAt: Long, head: Long): Boolean {
        if (chain == null || provenAt < 0 || head < provenAt) return false
        val report = reportAt(head) ?: return false
        if (address.hex !in report.watching) {
            watch(address)
            return false
        }
        // The proof's own block is checked too: if a reorg replaced it, a touch there says so.
        if (report.from > provenAt || report.to < head) return false
        val gaps = report.gaps[kind.json] ?: return false
        if (gaps.any { (low, high) -> low <= head && high >= provenAt }) return false
        return report.touched.none { it.address == address.hex && it.block in provenAt..head }
    }

    /** The report reaching [head], read again when the last one stops below it. */
    private fun reportAt(head: Long): Report? {
        last?.takeIf { it.to >= head }?.let { return it }
        val json = try {
            chain?.addressActivity((head - LOOKBACK).coerceAtLeast(0))
        } catch (e: Throwable) {
            // Throwable: the call crosses JNA, where a broken native library throws an Error.
            null
        } ?: return null
        return Report.parse(json)?.also { last = it }
    }

    private fun watch(address: Address) {
        try {
            // Read the report again next time, so it lists the address rather than this one
            // being pushed again at every reading until the head moves.
            if (chain?.setActivityWatch("""{"watch":["${address.hex}"]}""") == true) last = null
        } catch (e: Throwable) {
            // As for the report: no answer this time, the next reading asks again.
        }
    }

    /** One report, as far as this reads it. */
    private class Report(
        val from: Long,
        val to: Long,
        val gaps: Map<String, List<Pair<Long, Long>>>,
        val touched: List<Touch>,
        val watching: Set<String>,
    ) {
        class Touch(val address: String, val block: Long)

        companion object {
            /** Null for an `{"error":…}` object or anything else that is not a whole report. */
            fun parse(json: String): Report? = try {
                val root = Json.parseToJsonElement(json).jsonObject
                if ("error" in root) {
                    null
                } else {
                    val gaps = root.getValue("gaps").jsonObject.mapValues { (_, ranges) ->
                        ranges.jsonArray.map { range ->
                            val pair = range.jsonArray
                            require(pair.size == 2)
                            pair[0].jsonPrimitive.long to pair[1].jsonPrimitive.long
                        }
                    }
                    Report(
                        from = root.getValue("from").jsonPrimitive.long,
                        to = root.getValue("to").jsonPrimitive.long,
                        gaps = gaps,
                        touched = root.getValue("touched").jsonArray.map { touchOf(it.jsonObject) },
                        watching = (root.getValue("watching") as JsonArray)
                            .map { it.jsonPrimitive.content.lowercase() }
                            .toSet(),
                    )
                }
            } catch (e: Exception) {
                null
            }

            private fun touchOf(o: JsonObject) =
                Touch(o.getValue("address").jsonPrimitive.content.lowercase(), o.getValue("block").jsonPrimitive.long)
        }
    }

    private companion object {
        /** How far back each report asks: past the engine's 256-block window, so its lower gap shows. */
        const val LOOKBACK = 300L
    }
}
