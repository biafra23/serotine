package eth.serotine.data.engine

import eth.serotine.domain.repository.FinalizedBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The finalized block with its own timestamp, from its verified header (docs/swaps.md, *Proving an
 * order's end*: never its slot's time, which may be an empty slot's after it).
 *
 * - Each finalized block's header is read once: the finalized block moves once an epoch, and the
 *   status is polled every second.
 * - A header counts only when it is the block asked for (its `number`): the engine has answered a
 *   read for a past block from its head before (docs/swaps.md).
 * - After a read that failed, not again for [retryAfter], so a header the engine cannot serve does
 *   not cost every poll a read.
 * - Until the newest finalized block's header is had, the newest one that was had stands. It is
 *   finalized too, only older, so it can delay a proof or keep a hold-back longer, never shorten
 *   either.
 * - The header is read in [scope], never in the status poll that asks for it, which must not block
 *   (`VerifiedChain.snapshot`): a poll takes what is had, and a later one what the read brought.
 * - Safe from several threads, as every adapter's status poll asks: one read at a time.
 */
internal class FinalizedTimes(
    private val scope: CoroutineScope,
    private val retryAfter: Duration = 12.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    @Volatile
    private var had: FinalizedBlock? = null

    // Written only while holding [reading].
    private var failed: Pair<Long, TimeMark>? = null
    private val reading = Mutex()

    /**
     * The newest finalized block whose header was had, up to [block] (the engine's finalized block;
     * 0: none yet), with its timestamp (unix seconds); null while none was. Returns at once. When
     * [block]'s own header is not had, [mayRead] (the light client verifies reads now), no read is
     * running and none of it failed in the last [retryAfter], it is read in [scope] with
     * [readBlock] (the engine's verified `getBlockByNumber` of the block, without its
     * transactions, which may block).
     */
    fun of(block: Long, mayRead: Boolean = true, readBlock: (Long) -> String?): FinalizedBlock? {
        val known = had
        if (block <= (known?.number ?: 0) || !mayRead || !reading.tryLock()) return known
        val due = failed?.let { (number, at) -> number != block || at.elapsedNow() >= retryAfter } ?: true
        if (!due) {
            reading.unlock()
            return known
        }
        scope.launch {
            try {
                val time = try {
                    blockTimestampOf(readBlock(block), block)
                } catch (e: Exception) {
                    // An engine that fails, or a CancellationException its own code throws, is no answer.
                    null
                }
                if (time == null) {
                    failed = block to timeSource.markNow()
                } else {
                    failed = null
                    had = FinalizedBlock(block, time)
                }
            } finally {
                reading.unlock()
            }
        }
        return known
    }
}

/**
 * [inner] with each running snapshot's [EngineSnapshot.Running.finalizedHeader] filled from [times]:
 * the finalized block with its own header's timestamp, read through [inner]
 * ([VerifiedChain.blockByNumber], so a [LoggedChain] inside logs it as any request) while the
 * light client is synced and awake. Everything else is [inner]'s as it is.
 */
internal class FinalizedHeaders(
    private val inner: EngineChain,
    private val times: FinalizedTimes,
) : EngineChain by inner {

    override fun snapshot(): EngineSnapshot {
        val snapshot = inner.snapshot()
        if (snapshot !is EngineSnapshot.Running) return snapshot
        val mayRead = snapshot.phase == EngineSnapshot.Phase.SYNCED && !snapshot.paused
        return snapshot.copy(finalizedHeader = times.of(snapshot.finalizedBlock, mayRead, inner::blockByNumber))
    }
}

/**
 * The `timestamp` of the block object in [json] (`0x` hex), when its `number` is [block]; null for
 * anything else, another block's header included.
 */
internal fun blockTimestampOf(json: String?, block: Long): Long? {
    if (json == null) return null
    val header = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: return null
    fun quantity(key: String): Long? {
        val hex = (header[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return hex.removePrefix("0x").takeIf { it.isNotEmpty() && it.length <= 15 }?.toLongOrNull(16)
    }
    if (quantity("number") != block) return null
    return quantity("timestamp")
}
