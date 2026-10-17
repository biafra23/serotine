package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
 * Whatever [prove] throws, and a null from it, become [ReadUnavailable.UNPROVEN]: no answer
 * from anywhere else stands in.
 */
internal class ProvenReads<K, T>(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
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
                // Throwable: the snapshot crosses JNA too. A state that cannot be read is no
                // state yet, as the light client's own adapter treats it.
                val snapshot = try {
                    chain.snapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (snapshot == EngineSnapshot.Down) {
                    emit(EngineReading.Unavailable(ReadUnavailable.NO_ENGINE))
                    return@flow
                }
                val reading = if (snapshot == null) EngineReading.Syncing else readingAt(chain, key, snapshot)
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
            proofs[key]?.let { (provenAtHead, reading) -> if (provenAtHead == head) return reading }
            inFlight[key]?.takeIf { (at, _) -> at == head }?.second ?: run {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
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
