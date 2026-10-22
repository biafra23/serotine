package eth.serotine.data.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The finalized block's own timestamp, from its verified header (docs/swaps.md, *Proving an order's
 * end*: never its slot's time, which may be an empty slot's after it), remembered for the one block
 * it was read for: the finalized block moves once an epoch, the status is polled every second, so
 * its header is read once a move, and after a read that failed not again for [retryAfter], so a
 * header the engine cannot serve does not cost every poll a read. Not thread-safe: one per node,
 * used from its status poll alone.
 */
internal class FinalizedTimes(
    private val retryAfter: Duration = 12.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var known: Pair<Long, Long>? = null
    private var failed: Pair<Long, TimeMark>? = null

    /**
     * Block [block]'s timestamp (unix seconds): the one known for it, or else, when [mayRead] (the
     * light client verifies reads now) and no read of it failed in the last [retryAfter], read with
     * [readBlock] (the engine's verified `getBlockByNumber` for a `0x` block number, without its
     * transactions). Null when there is no finalized block yet (0) or its header is not had.
     */
    fun of(block: Long, mayRead: Boolean = true, readBlock: (String) -> String?): Long? {
        if (block <= 0) return null
        known?.let { (number, time) -> if (number == block) return time }
        if (!mayRead) return null
        failed?.let { (number, at) -> if (number == block && at.elapsedNow() < retryAfter) return null }
        val time = try {
            blockTimestampOf(readBlock("0x" + block.toString(16)))
        } catch (e: Exception) {
            // An engine that fails, or a CancellationException its own code throws, is no answer.
            null
        }
        if (time == null) {
            failed = block to timeSource.markNow()
            return null
        }
        known = block to time
        failed = null
        return time
    }
}

/** The `timestamp` of the block object in [json] (`0x` hex), or null for anything else. */
internal fun blockTimestampOf(json: String?): Long? {
    if (json == null) return null
    val block = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return null
    val hex = (block["timestamp"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    return hex.removePrefix("0x").takeIf { it.isNotEmpty() && it.length <= 15 }?.toLongOrNull(16)
}
