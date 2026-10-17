package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.BalanceReading.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/** One answer of [ProvenReads], before an adapter turns it into its port's reading. */
internal sealed interface EngineReading<out T> {

    /** No verified head to read at yet. */
    data object Syncing : EngineReading<Nothing>

    /** [value], proven at [blockNumber]. */
    data class Proven<T>(val value: T, val blockNumber: Long) : EngineReading<T>

    /** No verified answer, for [reason]. */
    data class Unavailable(val reason: Reason) : EngineReading<Nothing>
}

/**
 * Verified readings of one fact per address — what [prove] proves — over a verified light
 * client, shared by the engine adapters. Each subscriber polls the engine's state every
 * [pollInterval]; while the client can serve verified reads the fact is proven again whenever
 * the verified head has moved, and labelled with the block its proof anchors to. Whenever
 * reads cannot be verified (syncing, asleep, a stale head, no peer serving proofs) the
 * reading says so instead of keeping an old answer on screen.
 *
 * The last proof per address is shared by all subscribers, so one that re-subscribes on the
 * same head gets it at once instead of fetching it again; the [MAX_CACHED] most recently
 * proven addresses are kept, so addresses only looked at (the Watch screen) do not pile up. A device without an engine
 * ([chain] null) and an engine that is down answer [Reason.NO_ENGINE] once and stop.
 * Whatever [prove] throws, and a null from it, become [Reason.UNPROVEN]: no answer from
 * anywhere else stands in.
 */
internal class ProvenReads<T>(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
    /** The fact about an address and the block its proof anchors to, or null when unproven. Blocks. */
    private val prove: (VerifiedChain, Address) -> EngineReading.Proven<T>?,
) {
    /** The last proof per address, with the head it was fetched at. */
    private val proofs = mutableMapOf<Address, Pair<Long, EngineReading.Proven<T>>>()
    private val proofsLock = Mutex()

    fun observe(address: Address): Flow<EngineReading<T>> {
        val chain = chain ?: return flowOf(EngineReading.Unavailable(Reason.NO_ENGINE))
        return flow {
            while (true) {
                val snapshot = chain.snapshot()
                if (snapshot == EngineSnapshot.Down) {
                    emit(EngineReading.Unavailable(Reason.NO_ENGINE))
                    return@flow
                }
                emit(readingAt(chain, address, snapshot))
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private suspend fun readingAt(chain: VerifiedChain, address: Address, snapshot: EngineSnapshot): EngineReading<T> {
        if (snapshot !is EngineSnapshot.Running) return EngineReading.Syncing
        if (snapshot.phase == Phase.STALE_ANCHOR) return EngineReading.Unavailable(Reason.STALE_ANCHOR)
        val head = snapshot.readableHead ?: return EngineReading.Syncing
        proofsLock.withLock { proofs[address] }?.let { (provenAtHead, reading) ->
            if (provenAtHead == head) return reading
        }
        val proven = try {
            prove(chain, address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: engine calls cross JNA, where a broken native library fails with an
            // Error (UnsatisfiedLinkError), not an Exception.
            null
        } ?: return EngineReading.Unavailable(Reason.UNPROVEN)
        proofsLock.withLock {
            proofs.remove(address) // re-inserted last: the map's order is the order of proving
            proofs[address] = head to proven
            if (proofs.size > MAX_CACHED) proofs.remove(proofs.keys.first())
        }
        return proven
    }

    private companion object {
        /** Proofs kept: far more than the accounts a wallet shows at once. */
        const val MAX_CACHED = 64
    }
}
