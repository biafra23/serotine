package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import io.myotis.api.EngineException
import io.myotis.api.EnsResolutionResult
import io.myotis.api.EstimateResult
import io.myotis.api.SendResult

/** A Myotis gas estimate as the adapters read it: every status told apart, none dropped. */
internal fun estimateAnswer(result: EstimateResult): EstimateAnswer = when (result.status()) {
    EstimateResult.Status.OK -> EstimateAnswer.Gas(result.gas())
    EstimateResult.Status.REVERTED -> EstimateAnswer.Reverted(result.revertData()?.toHex().orEmpty())
    EstimateResult.Status.INFEASIBLE -> EstimateAnswer.Infeasible(result.detail().orEmpty())
    EstimateResult.Status.REFUSED -> EstimateAnswer.Refused(result.detail().orEmpty())
    EstimateResult.Status.UNAVAILABLE, null -> EstimateAnswer.Unavailable(result.detail())
}

/** A Myotis broadcast verdict as the adapters read it. */
internal fun sendAnswer(result: SendResult): SendAnswer = when (result.status()) {
    SendResult.Status.SENT -> SendAnswer.Sent("0x" + result.txHash().toHex())
    SendResult.Status.REJECTED -> SendAnswer.Rejected(result.detail())
    SendResult.Status.REFUSED -> SendAnswer.Refused(result.detail())
    SendResult.Status.UNAVAILABLE, null -> SendAnswer.Unavailable(result.detail())
}

/** A Myotis ENS answer as the adapters read it; an off-chain name told apart from a failure. */
internal fun nameAnswer(result: EnsResolutionResult): NameAnswer = NameAnswer(
    addressHex = result.addressHex(),
    blockNumber = result.blockNumber(),
    verified = result.verified(),
    error = result.error(),
    offchain = result.error()?.let(::isOffchainNameError) == true,
)

/**
 * [resolve] (an ENS call), with the engine's refusal of an off-chain name — no CCIP-Read gateway
 * is configured, by design — read as that, and nothing else it throws swallowed.
 */
internal fun nameAnswerOf(resolve: () -> EnsResolutionResult): NameAnswer = try {
    nameAnswer(resolve())
} catch (e: EngineException) {
    if (!isOffchainNameError(e.message.orEmpty())) throw e
    NameAnswer(null, -1, verified = false, error = e.message, offchain = true)
}
