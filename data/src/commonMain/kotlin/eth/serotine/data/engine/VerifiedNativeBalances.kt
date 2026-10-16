package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BalanceReading.Reason
import eth.serotine.domain.repository.NativeBalances
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.time.Duration

/**
 * [NativeBalances] over a verified light client. Each subscriber polls the engine's state
 * every [pollInterval]; while the client can serve verified reads it proves the balance
 * again whenever the verified head has moved, and labels each reading with the block its
 * proof anchors to. Whenever reads cannot be verified (syncing, asleep, a stale head, no
 * peer serving proofs) the reading says so instead of keeping an old amount on screen.
 *
 * The last proof per address is shared by all subscribers, so a screen that re-subscribes
 * on the same head shows it at once instead of fetching it again. A device without an
 * engine ([chain] null) and an engine that is down answer [Reason.NO_ENGINE] once and stop.
 * Whatever the engine throws becomes [Reason.UNPROVEN]: the port never fails, and no answer
 * from anywhere else stands in.
 */
class VerifiedNativeBalances internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : NativeBalances {

    /** The last proof per address, with the head it was fetched at. */
    private val proofs = mutableMapOf<Address, Pair<Long, BalanceReading.Verified>>()
    private val proofsLock = Mutex()

    override fun observeBalance(address: Address): Flow<BalanceReading> {
        val chain = chain ?: return flowOf(BalanceReading.Unavailable(Reason.NO_ENGINE))
        return flow {
            while (true) {
                val snapshot = try {
                    chain.snapshot()
                } catch (e: Throwable) {
                    // A cancellation of this poll ends it; anything else, a CancellationException
                    // the engine's own code throws included, is an engine failure.
                    currentCoroutineContext().ensureActive()
                    // Throwable, as for proofs: the engine's state crosses JNA too. This poll's
                    // reading says it is not verified, never an amount kept from before.
                    emit(BalanceReading.Unavailable(Reason.UNPROVEN))
                    delay(pollInterval)
                    continue
                }
                if (snapshot == EngineSnapshot.Down) {
                    emit(BalanceReading.Unavailable(Reason.NO_ENGINE))
                    return@flow
                }
                emit(readingAt(chain, address, snapshot))
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private suspend fun readingAt(chain: VerifiedChain, address: Address, snapshot: EngineSnapshot): BalanceReading {
        if (snapshot !is EngineSnapshot.Running) return BalanceReading.Syncing
        if (snapshot.phase == Phase.STALE_ANCHOR) return BalanceReading.Unavailable(Reason.STALE_ANCHOR)
        val head = snapshot.readableHead ?: return BalanceReading.Syncing
        proofsLock.withLock { proofs[address] }?.let { (provenAtHead, reading) ->
            if (provenAtHead == head) return reading
        }
        val proven = try {
            chain.proveBalance(address)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive() // as for the snapshot
            // Throwable: engine calls cross JNA, where a broken native library fails with an
            // Error (UnsatisfiedLinkError), not an Exception.
            null
        }
        val wei = proven?.let { Wei.parse(it.wei) } ?: return BalanceReading.Unavailable(Reason.UNPROVEN)
        val reading = BalanceReading.Verified(wei, proven.blockNumber)
        proofsLock.withLock { proofs[address] = head to reading }
        return reading
    }
}
