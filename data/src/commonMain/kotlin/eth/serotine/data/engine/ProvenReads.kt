package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** One answer of [ProvenReads], before an adapter turns it into its port's reading. */
internal sealed interface EngineReading<out T> {

    /** No verified head to read at yet, and no earlier proof of the fact. */
    data object Syncing : EngineReading<Nothing>

    /**
     * No verified head to read at, and [value] is the fact's last proof, at [blockNumber], made
     * at a verified head produced at [asOf] (unix seconds; null when unknown): it may have
     * changed since. An adapter that has no use for an older answer reads it as [Syncing].
     */
    data class LastKnown<T>(val value: T, val blockNumber: Long, val asOf: Long?) : EngineReading<T>

    /**
     * [value], proven at [blockNumber], whose hash is [blockHash] when the engine proved it: what
     * a proof kept across heads is checked against ([AddressActivity.unchangedSince]).
     */
    data class Proven<T>(val value: T, val blockNumber: Long, val blockHash: String? = null) : EngineReading<T>

    /** No verified answer, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : EngineReading<Nothing>
}

/**
 * Verified readings of one fact per key — an address, or a holder and a token: what [prove]
 * proves — over a verified light client, shared by the engine adapters. Each subscriber
 * polls the engine's state every [pollInterval]; while the client can serve verified reads
 * the fact is proven again whenever the verified head has moved — unless [unchangedSince] knows
 * that nothing since its proof touched it — and labelled with the block its proof anchors to. Whenever reads cannot be verified (syncing, asleep, a stale head, no
 * peer serving proofs) the reading says so: [EngineReading.Syncing], or, once the fact was
 * proven, [EngineReading.LastKnown] with that last proof, dated by the verified head it was made
 * at — in this process, or [kept] from an earlier one — an older answer labelled as one, never
 * passed off as current (owner's request, 2026-10-19). Every other failure (a refusal, a stale
 * anchor, an unproven read) says so without it.
 *
 * The last proof per key is shared by all subscribers, so one that re-subscribes on the same
 * head gets it at once instead of fetching it again, and one that arrives while it is being
 * fetched waits for that fetch — or, when the subscriber making it is cancelled midway (a
 * one-shot check that gave up, a screen left), makes it again itself, since a read cut off says
 * nothing of the fact; the [MAX_CACHED] most recently proven keys are kept, so keys
 * only looked at (the Watch screen) do not pile up. A device without
 * an engine ([chain] null) and an engine that is down answer [ReadUnavailable.NO_ENGINE]
 * once and stop, and so does a key the engine refuses ([ReadUnavailable.REFUSED]).
 * Whatever [prove] throws, and a null from it, become [ReadUnavailable.UNPROVEN], and so does
 * an engine state that cannot be read, for that poll — never an answer kept from before; the
 * poll goes on. A cancellation of the poll itself ends it, while a `CancellationException`
 * the engine's own code throws is a failure like any other. No answer from anywhere else
 * stands in.
 */
internal class ProvenReads<K, T>(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
    /**
     * For how many verified heads a proof of a given fact stands before it is made again: 1,
     * at every head; more for one that takes many proofs and rarely changes (a Safe's owners).
     * Whatever it is, a reading is labelled with the block its proof anchors to.
     */
    private val keptForHeads: (T) -> Long = { 1 },
    /**
     * Within how many heads a subscriber joins a fetch already in flight for its key, rather
     * than starting its own: 1, at the same head only; as long as [keptForHeads] at most, for
     * a fetch that can take several heads.
     */
    private val sharedForHeads: Long = 1,
    /**
     * Whether the proof of a key's fact made at block `provenAt`, of hash `provenHash` (null when
     * the engine did not prove it), still stands at the verified head `head` without being made
     * again ([AddressActivity.unchangedSince]): true only when nothing could have changed it in
     * between. Never blocks on I/O. False by default: a proof stands as long as [keptForHeads]
     * says. Either way the reading keeps the block of its proof.
     */
    private val unchangedSince: (key: K, provenAt: Long, provenHash: String?, head: Long) -> Boolean =
        { _, _, _, _ -> false },
    /**
     * A kept proof's value as of a later verified head produced at `headTimestamp` (unix
     * seconds; null when unknown), for a fact whose reading also depends on the head's time
     * — a price's age. Asked only when [unchangedSince] vouched for the proof; null proves the
     * fact again. The value as it is by default.
     */
    private val atHead: (key: K, value: T, headTimestamp: Long?) -> T? = { _, value, _ -> value },
    /**
     * Where the last proof of each key is kept beyond the process ([KeptProofs]), so that a
     * restart can say what was last verified before a head can be read; none by default.
     */
    private val kept: KeptProofs<K, T> = KeptProofs.none(),
    /**
     * Whether a stopped network's reading offers the last proof ([EngineReading.LastKnown]), as a
     * balance does; otherwise, and with no proof, it says the network is off
     * ([ReadUnavailable.NETWORK_OFF]): an adapter that reads a last proof as syncing would say
     * "checking" for as long as the network stays off.
     */
    private val offersLastKnownWhenStopped: Boolean = false,
    /**
     * The fact about a key and the block its proof anchors to; a refusal ([EngineReading.Unavailable]);
     * or null when unproven. Blocks, and may suspend between its blocking calls (to check its
     * cancellation, or to pause before a retry).
     */
    private val prove: suspend (VerifiedChain, K) -> EngineReading<T>?,
) {
    /** The last proof per key, with the head it was fetched at and that head's time. */
    private val proofs = mutableMapOf<K, Kept<T>>()

    /** A [reading] proven at verified head [head], produced at [headTimestamp] (unix seconds; null when unknown). */
    private class Kept<T>(val head: Long, val headTimestamp: Long?, val reading: EngineReading.Proven<T>)

    /**
     * The read being made per key, with the head it is made at: a subscriber that finds one
     * for its head waits for it rather than making the same blocking calls again.
     */
    private val inFlight = mutableMapOf<K, Pair<Long, CompletableDeferred<EngineReading<T>?>>>()
    private val proofsLock = Mutex()

    fun observe(key: K): Flow<EngineReading<T>> {
        val chain = chain ?: return flowOf(EngineReading.Unavailable(ReadUnavailable.NO_ENGINE))
        return flow {
            while (true) {
                val snapshot = try {
                    chain.snapshot()
                } catch (e: Throwable) {
                    // A cancellation of this poll ends it; anything else, a CancellationException
                    // the engine's own code throws included, is an engine failure.
                    currentCoroutineContext().ensureActive()
                    // Throwable, as for proofs: the engine's state crosses JNA too. This poll's
                    // reading says it is not verified, never an answer kept from before.
                    emit(UNPROVEN)
                    delay(pollInterval)
                    continue
                }
                if (snapshot == EngineSnapshot.Down) {
                    emit(EngineReading.Unavailable(ReadUnavailable.NO_ENGINE))
                    return@flow
                }
                val reading = readingAt(chain, key, snapshot)
                emit(reading)
                // A refusal is for good on this build: asking again on every tick changes nothing.
                if (reading == REFUSED) return@flow
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private suspend fun readingAt(chain: VerifiedChain, key: K, snapshot: EngineSnapshot): EngineReading<T> {
        // A stopped network: what was last verified, dated, or that it is off; never "syncing".
        if (snapshot is EngineSnapshot.Stopped) {
            val last = if (offersLastKnownWhenStopped) lastKnown(key) else null
            return last ?: EngineReading.Unavailable(ReadUnavailable.NETWORK_OFF)
        }
        // A start that failed: no light client now, but the stream polls on for its next start.
        if (snapshot == EngineSnapshot.Failed) return EngineReading.Unavailable(ReadUnavailable.NO_ENGINE)
        if (snapshot !is EngineSnapshot.Running) return syncing(key)
        if (snapshot.phase == Phase.STALE_ANCHOR) return EngineReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        val head = snapshot.readableHead ?: return syncing(key)
        // Asked outside the lock: it reads the engine (in memory, but across JNA).
        proofsLock.withLock { proofs[key] }?.let { kept ->
            if (head >= kept.head && unchangedSince(key, kept.reading.blockNumber, kept.reading.blockHash, head)) {
                atHead(key, kept.reading.value, snapshot.headTimestamp)?.let {
                    return kept.reading.copy(value = it)
                }
            }
        }
        while (true) {
            val pending = CompletableDeferred<EngineReading<T>?>()
            val running = proofsLock.withLock {
                proofs[key]?.let { kept ->
                    if (head - kept.head in 0 until keptForHeads(kept.reading.value)) return kept.reading
                }
                inFlight[key]?.takeIf { (at, _) -> head - at in 0 until sharedForHeads }?.second ?: run {
                    inFlight[key] = head to pending
                    null
                }
            }
            if (running != null) {
                // Null: the subscriber that made it was cancelled midway, which says nothing of the
                // fact. This one makes the read itself, or joins whoever has started it since.
                running.await()?.let { return it }
                continue
            }
            // Null unless the read completes: a waiter is never left without an answer.
            var reading: EngineReading<T>? = null
            try {
                reading = prove(chain, key, head, snapshot.headTimestamp)
                return reading
            } finally {
                withContext(NonCancellable) {
                    proofsLock.withLock { if (inFlight[key]?.second === pending) inFlight.remove(key) }
                }
                pending.complete(reading)
            }
        }
    }

    /**
     * No head to read at: the last proof of [key], dated, when there is one — made in this
     * process, or else [kept] from an earlier one; [EngineReading.Syncing] otherwise.
     */
    private suspend fun syncing(key: K): EngineReading<T> = lastKnown(key) ?: EngineReading.Syncing

    /** The last proof of [key], dated: made in this process, or else [kept] from an earlier one; null for none. */
    private suspend fun lastKnown(key: K): EngineReading<T>? = proofsLock.withLock {
        proofs[key]?.let { EngineReading.LastKnown(it.reading.value, it.reading.blockNumber, it.headTimestamp) }
    } ?: kept.load(key)

    /** One read of [key] at [head], produced at [headTimestamp], kept when it is a proof. */
    private suspend fun prove(chain: VerifiedChain, key: K, head: Long, headTimestamp: Long?): EngineReading<T> {
        val proven = try {
            prove(chain, key)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive() // as for the snapshot
            // Throwable: engine calls cross JNA, where a broken native library fails with an
            // Error (UnsatisfiedLinkError), not an Exception.
            null
        } ?: return UNPROVEN
        if (proven !is EngineReading.Proven) return proven // a refusal: an answer, not a proof to keep
        proofsLock.withLock {
            proofs.remove(key) // re-inserted last: the map's order is the order of proving
            proofs[key] = Kept(head, headTimestamp, proven)
            if (proofs.size > MAX_CACHED) proofs.remove(proofs.keys.first())
        }
        kept.save(key, EngineReading.LastKnown(proven.value, proven.blockNumber, headTimestamp))
        return proven
    }

    private companion object {
        val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)
        val UNPROVEN = EngineReading.Unavailable(ReadUnavailable.UNPROVEN)

        /** Proofs kept: far more than the accounts a wallet shows at once. */
        const val MAX_CACHED = 64
    }
}
