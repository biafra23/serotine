package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
 * engine's address-activity report: true only when the proof's block is the canonical one, and
 * every block from it through the head was checked for that address and none touched it.
 * Anything the engine did not check — a block before it followed, a gap, a report it could not
 * give — is "not known unchanged", and the proof is made again, as without this.
 *
 * **Reorgs.** A proof names the hash of the block it was verified at (Myotis ABI 52: the beacon
 * anchor vouched for a block of that number with the proof's state root). It stands only while
 * the engine's follower holds that same hash for that block, its report's `base`: a proof from
 * a sibling the chain left, or one whose block a later reorg replaced (or any block below it),
 * is made again. A proof that names no hash, or whose block the follower does not hold, never
 * stands. (A replaced block that touched the address no longer shows in the report, which is
 * why a touch check alone could not catch this.) The hash and the blocks after it come from one
 * report, read after the proof's block for the head being asked about and shared only by the
 * readings of one poll: a report kept from another head or an earlier poll never vouches.
 *
 * **The watch list** follows what readings ask about: an address is watched the first time it
 * is asked about, so the answer for it is false until the engine's next report lists it; one
 * not asked about for [IDLE_HEADS] heads is unwatched, so the list does not only grow. The
 * watch list's report is read once per verified head, however many readings ask, and once more
 * while its newest block is still a gap (the follower lags the head by a moment). One that no longer
 * lists an address (the engine restarted) watches it again; a push the engine refused is not
 * repeated for [REFUSED_BACKOFF_HEADS] heads.
 */
internal class AddressActivity(
    private val chain: ActivityChain?,
    /** What a report's age is measured with ([TOP_GAP_RETRY]). */
    private val clock: TimeSource = TimeSource.Monotonic,
) {

    /** What a fact about an address changes with. */
    enum class Kind(val json: String) {
        /** The account itself: its balance, nonce or code (the engine's block-access-list coverage). */
        ACCOUNT("account"),

        /** A log naming the address, such as a token `Transfer` (the engine's logs-bloom coverage). */
        LOG("log"),
    }

    /** The last report read, when, and whether its newest block was a gap. */
    @Volatile
    private var last: Fetched? = null

    private class Fetched(val report: Report, val at: TimeMark, val reread: Boolean) {
        /** Whether this answers for [head]: it reaches it, and is not a top gap due its one re-read. */
        fun serves(head: Long): Boolean {
            val topGap = report.to == head && report.gapAtTop()
            return report.to >= head && !(topGap && !reread && at.elapsedNow() >= TOP_GAP_RETRY)
        }
    }

    /** Per proof block, the report after it and the head it was read for ([reportAfter]). */
    @Volatile
    private var checks: Map<Long, Pair<Long, Fetched>> = emptyMap()

    /** The head each watched address was last asked about at. */
    @Volatile
    private var askedAt: Map<String, Long> = emptyMap()

    /** The head a watch push was last refused at, per address. */
    @Volatile
    private var refusedAt: Map<String, Long> = emptyMap()

    /** The head idle addresses were last unwatched at. */
    @Volatile
    private var prunedAt: Long = -1

    /**
     * Whether the [kind] of fact about [address] proven at block [provenAt], of hash [provenHash]
     * (null: the engine did not prove it), is known not to have changed by the verified head
     * [head]. Never blocks on I/O.
     */
    fun unchangedSince(address: Address, kind: Kind, provenAt: Long, provenHash: String?, head: Long): Boolean =
        unchangedSince(listOf(address to kind), provenAt, provenHash, head)

    /**
     * Whether every one of [facts] — an address and the kind of fact about it — proven at block
     * [provenAt], of hash [provenHash], is known not to have changed by the verified head [head]:
     * all answered from one report, so a reading that rests on several facts (a price: its
     * aggregator's logs and its proxy's own state) is never checked against two readings of the
     * chain. Never blocks on I/O.
     */
    fun unchangedSince(facts: List<Pair<Address, Kind>>, provenAt: Long, provenHash: String?, head: Long): Boolean {
        if (chain == null || provenAt < 0 || head < provenAt || facts.isEmpty()) return false
        val addresses = facts.map { it.first }.distinct()
        askedAt = askedAt + addresses.map { it.hex to head }
        val report = reportAt(head) ?: return false
        pruneIdle(report, head)
        val unwatched = addresses.filter { it.hex !in report.watching }
        if (unwatched.isNotEmpty()) {
            unwatched.forEach { watch(it, head) }
            return false
        }
        provenHash ?: return false
        // One read for the whole check, made for this head: the proof's block as the follower
        // holds it now and the blocks after it, from the same chain. A report kept from a head
        // a reorg has since lowered or replaced never answers it.
        val since = reportAfter(provenAt, head) ?: return false
        if (since.from > provenAt + 1 || since.to < head) return false
        // The proof's own block is the canonical one: not a sibling the chain left, and not
        // replaced since by a reorg below the head.
        if (since.base?.first != provenAt || since.base.second != provenHash.lowercase()) return false
        return facts.all { (address, kind) ->
            // Watched by the very report that vouches: one that stopped watching the address
            // (unwatched meanwhile, an engine restart) lists none of its touches.
            if (address.hex !in since.watching) return@all false
            val gaps = since.gaps[kind.json] ?: return@all false
            if (gaps.any { (low, high) -> low <= head && high >= provenAt }) return@all false
            // From the block after the proof's: what the proof's own block did is in the proof.
            since.touched.none { it.address == address.hex && it.block in (provenAt + 1)..head }
        }
    }

    /**
     * Whether the chain's blocks up to [head] show account activity at all: false on one without
     * block access lists (before Amsterdam), where nothing gated on [Kind.ACCOUNT] can stand.
     * Reads the report for [head] when the last one stops below it.
     */
    fun hasAccountCoverage(head: Long): Boolean {
        if (chain == null || head < 0) return false
        val report = reportAt(head) ?: return false
        val gaps = report.gaps[Kind.ACCOUNT.json] ?: return false
        return !(gaps.size == 1 && gaps[0].first <= report.from && gaps[0].second >= report.to)
    }

    /**
     * The report reaching [head], for the watch list and [hasAccountCoverage]: read again when
     * the last one stops below it, or once more when its newest block was still a gap. Never
     * what vouches for a proof ([reportAfter] is).
     */
    private fun reportAt(head: Long): Report? {
        last?.let { if (it.serves(head)) return it.report }
        val report = fetch((head - LOOKBACK).coerceAtLeast(0)) ?: return null
        last = Fetched(report, clock.markNow(), reread = last?.report?.to == report.to)
        return report
    }

    /**
     * The report after [provenAt] read for [head]: its `base` and its blocks come from one read.
     * Shared by the readings of one poll — the same head, within [CHECK_SHARED] — and read again
     * otherwise, so a reorg that keeps the head's number (or lowers it and climbs back between
     * two polls) is seen at the next poll, not at the next head.
     */
    private fun reportAfter(provenAt: Long, head: Long): Report? {
        checks[provenAt]?.takeIf { (at, fetched) -> at == head && fetched.at.elapsedNow() < CHECK_SHARED }
            ?.let { return it.second.report }
        val report = fetch(provenAt) ?: return null
        val fetched = Fetched(report, clock.markNow(), reread = false)
        checks = (checks + (provenAt to (head to fetched))).let { if (it.size > MAX_CHECKS) it - it.keys.min() else it }
        return report
    }

    private fun fetch(afterBlock: Long): Report? =
        read { chain?.addressActivity(afterBlock) }?.let { Report.parse(it) }

    private fun watch(address: Address, head: Long) {
        if (refusedAt[address.hex]?.let { head - it < REFUSED_BACKOFF_HEADS } == true) return
        val taken = read { chain?.setActivityWatch("""{"watch":["${address.hex}"]}""") } == true
        // Taken: read the report again next time, so it lists the address rather than this one
        // being pushed again at every reading until the head moves.
        if (taken) last = null else refusedAt = refusedAt + (address.hex to head)
    }

    /** Unwatch the addresses no reading asked about for [IDLE_HEADS] heads, once per head. */
    private fun pruneIdle(report: Report, head: Long) {
        if (prunedAt == head) return
        prunedAt = head
        val idle = report.watching.filter { (askedAt[it] ?: -1) < head - IDLE_HEADS }
        // Never asked in this process: watched by an earlier one's pushes, or just restored.
        val known = idle.filter { it in askedAt }
        if (known.isEmpty()) return
        val list = known.joinToString(",") { "\"$it\"" }
        if (read { chain?.setActivityWatch("""{"unwatch":[$list]}""") } == true) {
            askedAt = askedAt - known.toSet()
            last = null
        }
    }

    /** [call], or null when it throws: engine calls cross JNA, where a broken library throws an Error. */
    private inline fun <T> read(call: () -> T?): T? = try {
        call()
    } catch (e: Throwable) {
        null
    }

    /** One report, as far as this reads it. */
    private class Report(
        /** The block the report starts after and the hash the follower holds for it; null when it holds none. */
        val base: Pair<Long, String>?,
        val from: Long,
        val to: Long,
        val gaps: Map<String, List<Pair<Long, Long>>>,
        val touched: List<Touch>,
        val watching: Set<String>,
    ) {
        class Touch(val address: String, val block: Long)

        /** Whether the newest block is in a gap of either kind. */
        fun gapAtTop(): Boolean = gaps.values.any { ranges -> ranges.any { (_, high) -> high >= to } }

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
                        base = base(root),
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

            /** A report's `base`: the block it starts after and the hash the engine holds for it. */
            private fun base(root: JsonObject): Pair<Long, String>? {
                val base = root.getValue("base").jsonObject
                val hash = base.getValue("hash").takeIf { it !is JsonNull }?.jsonPrimitive?.content?.lowercase()
                return hash?.let { base.getValue("block").jsonPrimitive.long to it }
            }

            private fun touchOf(o: JsonObject) =
                Touch(o.getValue("address").jsonPrimitive.content.lowercase(), o.getValue("block").jsonPrimitive.long)
        }
    }

    private companion object {
        /** How far back each report asks: past the engine's 256-block window, so its lower gap shows. */
        const val LOOKBACK = 300L

        /** Heads an address may go unasked before it is unwatched: about an hour on Sepolia. */
        const val IDLE_HEADS = 300L

        /** Heads a refused watch push waits before it is pushed again. */
        const val REFUSED_BACKOFF_HEADS = 50L

        /** Proof blocks whose reports are kept: more than the engine's window. */
        const val MAX_CHECKS = 512

        /** How long a report whose newest block was a gap serves before it is read once more. */
        val TOP_GAP_RETRY = 2.seconds

        /** How long a proof block's report serves the readings of one poll. */
        val CHECK_SHARED = 1.seconds
    }
}
