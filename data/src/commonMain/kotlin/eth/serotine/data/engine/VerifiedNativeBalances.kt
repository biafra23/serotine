package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BalanceReading.Reason
import eth.serotine.domain.repository.NativeBalances
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Duration

/**
 * What the engine adapters need from a verified light client. Its calls block (they cross
 * into the engine), so they run on the adapters' dispatcher, never a caller's thread.
 */
internal interface VerifiedChain {

    /** The light client's state right now, as far as reads are concerned. */
    fun status(): ChainStatus

    /** The light client's progress right now, for the user to watch. */
    fun beacon(): BeaconSnapshot

    /**
     * The balance of [address] in decimal wei at block [blockNumber], proven against the
     * light client's verified state; null when the engine cannot prove it.
     */
    fun balanceWei(address: Address, blockNumber: Long): String?
}

/**
 * The light client's progress as the engine reports it (`ChainHandle.beaconStatus()`), or
 * why there is none.
 */
internal sealed interface BeaconSnapshot {

    /** The engine is still starting. */
    data object Booting : BeaconSnapshot

    /** There is no engine, or it failed to start. */
    data object Down : BeaconSnapshot

    /** The engine's report; see `io.myotis.api.BeaconStatus` for each field. */
    data class Running(
        val phase: Phase,
        val bootstrapped: Boolean,
        val currentPeriod: Long,
        val targetPeriod: Long,
        val servingPeers: Int,
        val knownStateRoots: Int,
        val fillThreshold: Int,
        val headBlock: Long?,
        val wsBoundPeriods: Long,
    ) : BeaconSnapshot

    /** `io.myotis.api.BeaconState`. */
    enum class Phase { STARTING, SYNCING, CATCHING_UP, SYNCED, STALE_ANCHOR }
}

/** The light client's state, as far as reads are concerned. */
internal sealed interface ChainStatus {

    /** Not started, syncing or catching up: no verified head to read at. */
    data object Syncing : ChainStatus

    /** Sync refused: the trust anchor is older than the weak-subjectivity bound. */
    data object StaleAnchor : ChainStatus

    /** The engine failed to start, so there is no light client to read from. */
    data object Down : ChainStatus

    /** Synced, with [headBlock] the verified execution head. */
    data class Synced(val headBlock: Long) : ChainStatus
}

/**
 * [NativeBalances] over a verified light client: each subscriber polls the client's state
 * every [pollInterval] and reads the balance again only when the verified head has moved,
 * at exactly that head. A device without a client ([chain] null) answers
 * [Reason.NO_ENGINE] once. Whatever the engine throws becomes [Reason.UNPROVEN]: the port
 * never fails, and no answer from anywhere else stands in.
 */
class VerifiedNativeBalances internal constructor(
    private val chain: VerifiedChain?,
    private val dispatcher: CoroutineDispatcher,
    private val pollInterval: Duration,
) : NativeBalances {

    override fun observeBalance(address: Address): Flow<BalanceReading> {
        val chain = chain ?: return flowOf(BalanceReading.Unavailable(Reason.NO_ENGINE))
        return flow {
            var verified: BalanceReading.Verified? = null
            while (true) {
                val reading = try {
                    when (val status = chain.status()) {
                        ChainStatus.Syncing -> BalanceReading.Syncing
                        ChainStatus.StaleAnchor -> BalanceReading.Unavailable(Reason.STALE_ANCHOR)
                        ChainStatus.Down -> BalanceReading.Unavailable(Reason.NO_ENGINE)
                        is ChainStatus.Synced ->
                            verified?.takeIf { it.blockNumber == status.headBlock }
                                ?: read(chain, address, status.headBlock)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable: engine calls cross JNA, where a broken native library fails
                    // with an Error (UnsatisfiedLinkError), not an Exception.
                    BalanceReading.Unavailable(Reason.UNPROVEN)
                }
                if (reading is BalanceReading.Verified) verified = reading
                emit(reading)
                delay(pollInterval)
            }
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    private fun read(chain: VerifiedChain, address: Address, head: Long): BalanceReading =
        chain.balanceWei(address, head)?.let(Wei::parse)?.let { BalanceReading.Verified(it, head) }
            ?: BalanceReading.Unavailable(Reason.UNPROVEN)
}
