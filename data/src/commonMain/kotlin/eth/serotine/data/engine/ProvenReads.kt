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

    /** No verified head to read at yet. */
    data object Syncing : EngineReading<Nothing>

    /** [value], proven at [blockNumber]. */
    data class Proven<T>(val value: T, val blockNumber: Long) : EngineReading<T>

    /** No verified answer, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : EngineReading<Nothing>
}

/**
 * Verified readings of one fact per key — an address, or a holder and a token: what [prove]
 * proves — over a verified light client, shared by the engine adapters. Each subscriber
 * polls the engine's state every [pollInterval]; while the client can serve verified reads
 * the fact is proven again whenever the verified head has moved, and labelled with the block
 * its proof anchors to. Whenever reads cannot be verified (syncing, asleep, a stale head, no
 * peer serving proofs) the reading says so instead of keeping an old answer on screen.
 *
 * The last proof per key is shared by all subscribers, so one that re-subscribes on the same
 * head gets it at once instead of fetching it again, and one that arrives while it is being
 * fetched waits for that fetch; the [MAX_CACHED] most recently proven keys are kept, so keys
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
     * The fact about a key and the block its proof anchors to; a refusal ([EngineReading.Unavailable]);
     * or null when unproven. Blocks.
     */
    private val prove: (VerifiedChain, K) -> EngineReading<T>?,
) {
    /** The last proof per key, with the head it was fetched at. */
    private val proofs = mutableMapOf<K, Pair<Long, EngineReading.Proven<T>>>()

    /**
     * The read being made per key, with the head it is made at: a subscriber that finds one
     * for its head waits for it rather than making the same blocking calls again.
     */
    private val inFlight = mutableMapOf<K, Pair<Long, CompletableDeferred<EngineReading<T>>>>()
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
        if (snapshot !is EngineSnapshot.Running) return EngineReading.Syncing
        if (snapshot.phase == Phase.STALE_ANCHOR) return EngineReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        val head = snapshot.readableHead ?: return EngineReading.Syncing
        val pending = CompletableDeferred<EngineReading<T>>()
        val running = proofsLock.withLock {
            proofs[key]?.let { (provenAtHead, reading) ->
                if (head - provenAtHead in 0 until keptForHeads(reading.value)) return reading
            }
            inFlight[key]?.takeIf { (at, _) -> head - at in 0 until sharedForHeads }?.second ?: run {
                inFlight[key] = head to pending
                null
            }
        }
        if (running != null) return running.await()
        // Unproven unless the read completes: a waiter is never left without an answer.
        var reading: EngineReading<T> = UNPROVEN
        try {
            reading = prove(chain, key, head)
            return reading
        } finally {
            withContext(NonCancellable) {
                proofsLock.withLock { if (inFlight[key]?.second === pending) inFlight.remove(key) }
            }
            pending.complete(reading)
        }
    }

    /** One read of [key] at [head], kept when it is a proof. */
    private suspend fun prove(chain: VerifiedChain, key: K, head: Long): EngineReading<T> {
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
            proofs[key] = head to proven
            if (proofs.size > MAX_CACHED) proofs.remove(proofs.keys.first())
        }
        return proven
    }

    private companion object {
        val REFUSED = EngineReading.Unavailable(ReadUnavailable.REFUSED)
        val UNPROVEN = EngineReading.Unavailable(ReadUnavailable.UNPROVEN)

        /** Proofs kept: far more than the accounts a wallet shows at once. */
        const val MAX_CACHED = 64
    }
}
