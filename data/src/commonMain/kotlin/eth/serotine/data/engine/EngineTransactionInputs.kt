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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [TransactionInputs] over the engine's verified reads ([TransactingChain]): asked only while
 * the light client can verify reads at its head ([whyUnreadable]), on [dispatcher], and an
 * engine that fails, or a CancellationException its own code throws, is no answer — as for
 * every verified read ([ProvenReads]). A device without an engine ([chain] null) answers
 * [ReadUnavailable.NO_ENGINE].
 */
class EngineTransactionInputs internal constructor(
    private val chain: TransactingChain?,
    private val dispatcher: CoroutineDispatcher,
) : TransactionInputs {

    override suspend fun nonce(account: Address): NonceReading = ask(NonceReading::Unavailable) { chain ->
        chain.pendingNonce(account)?.takeIf { it >= 0 }?.let(NonceReading::Read)
            ?: NonceReading.Unavailable(ReadUnavailable.UNPROVEN)
    }

    override suspend fun fees(): FeeReading = ask(FeeReading::Unavailable) { chain ->
        chain.feeHistory(FEE_BLOCKS, EngineJson.PERCENTILES)?.let(EngineJson::feeQuote)?.let(FeeReading::Quoted)
            ?: FeeReading.Unavailable(ReadUnavailable.UNPROVEN)
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
                EstimateAnswer.Unavailable -> GasEstimate.Unavailable(ReadUnavailable.UNPROVEN)
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
    }
}
