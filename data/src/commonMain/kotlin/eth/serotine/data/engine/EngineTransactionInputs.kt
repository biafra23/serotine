package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [TransactionInputs] over the engine's verified reads ([TransactingChain]): asked only while
 * the light client can verify reads at its head ([whyUnreadable]), on [dispatcher], and an
 * engine that fails, or a CancellationException its own code throws, is no answer — as for
 * every verified read ([ProvenReads]). A device without an engine ([chain] null) answers
 * [ReadUnavailable.NO_ENGINE].
 *
 * The fee history is asked again while it is cold ([fees]): every [feeRetryInterval], for up to
 * [feeWait] in all, measured on [timeSource].
 */
class EngineTransactionInputs internal constructor(
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
    private val feeRetryInterval: Duration = 2_500.milliseconds,
    private val feeWait: Duration = 15.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TransactionInputs {

    override suspend fun nonce(account: Address): NonceReading = ask(NonceReading::Unavailable) { chain ->
        chain.pendingNonce(account)?.takeIf { it >= 0 }?.let(NonceReading::Read)
            ?: NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
    }

    override suspend fun minedNonce(account: Address): NonceReading = ask(NonceReading::Unavailable) { chain ->
        chain.minedNonce(account)?.takeIf { it >= 0 }?.let(NonceReading::Read)
            ?: NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
    }

    /**
     * The fee levels of the verified fee history. The engine's history is cold on its first request
     * after a start, and on one more than 120 s after the last: it builds ten blocks of it, waits
     * only 5 s for that, and answers nothing ("still being computed; retry shortly"), and a caller
     * right behind gets nothing at once ("the build just failed"). The build goes on, so a request a
     * few seconds later is answered. So an empty answer is asked again every [feeRetryInterval],
     * for up to [feeWait] in all; the wait is a [delay] between the blocking reads, so a quote
     * cancelled meanwhile stops at the next one. Only an engine that answered nothing is asked
     * again: one that cannot verify reads, or none at all, is said at once.
     */
    override suspend fun fees(): FeeReading {
        val started = timeSource.markNow()
        while (true) {
            val reading = ask(FeeReading::Unavailable) { chain ->
                chain.feeHistory(FEE_BLOCKS, EngineJson.PERCENTILES)
                    ?.let(EngineJson::feeQuote)
                    ?.let(FeeReading::Quoted)
                    ?: COLD
            }
            if (reading !== COLD) return reading
            // No room for another wait before the bound: no answer.
            if (started.elapsedNow() + feeRetryInterval >= feeWait) return COLD
            delay(feeRetryInterval)
        }
    }

    override suspend fun estimateGas(from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate =
        ask(GasEstimate::Unavailable) { chain ->
            when (val answer = chain.estimateGas(from, to, value.toDecimal(), data)) {
                // No transaction takes no gas: a non-positive estimate is no answer.
                is EstimateAnswer.Gas -> answer.gas.takeIf { it > 0 }?.let(GasEstimate::Estimated)
                    ?: GasEstimate.Unavailable(ReadUnavailable.UNPROVEN)
                is EstimateAnswer.Reverted -> GasEstimate.Reverted(answer.revertHex)
                is EstimateAnswer.Infeasible -> GasEstimate.Infeasible(answer.detail)
                is EstimateAnswer.Refused -> GasEstimate.Refused(answer.detail)
                is EstimateAnswer.Unavailable -> GasEstimate.Unavailable(ReadUnavailable.UNPROVEN, answer.detail)
            }
        }

    override suspend fun simulate(from: Address, to: Address, value: UInt256, data: ByteArray): Simulation =
        ask(Simulation::Unavailable) { chain ->
            when (val outcome = chain.callFrom(from, to, value.toDecimal(), data)) {
                is CallOutcome.Returned -> Simulation.Succeeded(outcome.hex, outcome.blockNumber)
                is CallOutcome.Reverted -> Simulation.Reverted(outcome.blockNumber)
                CallOutcome.Refused -> Simulation.Refused
                null -> Simulation.Unavailable(ReadUnavailable.UNPROVEN)
            }
        }

    /** [read] on [dispatcher] while reads can be verified; otherwise, or when the engine fails, [unavailable]. */
    private suspend fun <T> ask(unavailable: (ReadUnavailable) -> T, read: (TransactingChain) -> T): T {
        val chain = chain ?: return unavailable(ReadUnavailable.NO_ENGINE)
        return withContext(dispatcher) {
            try {
                whyUnreadable(chain.snapshot())?.let { return@withContext unavailable(it) }
                read(chain)
            } catch (e: Throwable) {
                // A cancellation of this call ends it; anything else, a CancellationException the
                // engine's own code throws included, is an engine failure (as in ProvenReads).
                currentCoroutineContext().ensureActive()
                unavailable(ReadUnavailable.UNPROVEN)
            }
        }
    }

    private companion object {
        /** The blocks the fee history covers: about two minutes of Sepolia. */
        const val FEE_BLOCKS = 10

        /** An engine that answered no fee history (or one it could not read): asked again, while time is left. */
        val COLD = FeeReading.Unavailable(ReadUnavailable.UNPROVEN)
    }
}
