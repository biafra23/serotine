package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.NetworkUpgrade
import io.myotis.engine.ios.CallResult
import io.myotis.engine.ios.EnsResolutionResult
import io.myotis.engine.ios.EstimateResult
import io.myotis.engine.ios.SendResult
import io.myotis.engine.ios.UpgradeAdvisory
import io.myotis.engine.ios.UpgradePhase

// The iOS engine's answers as the adapters read them: the same readings as the JVM side's
// (jvmSharedMain: CallOutcomes.kt, TransactionAnswers.kt, UpgradeAdvisories.kt), over the
// iOS module's types, which carry `io.myotis.api`'s names and meanings.

/**
 * A call result: OK and REVERTED are answers, REFUSED and INFEASIBLE (calldata alone
 * outran the engine's budget) permanent refusals, UNAVAILABLE no verified answer right now.
 * The block is the one the call ran against, -1 when the engine does not say, with its hash
 * when the engine proved it.
 */
internal fun callOutcome(result: CallResult): CallOutcome? {
    val block = result.blockNumber.takeIf { it >= 0 }
    val hash = result.blockHashHex?.lowercase()
    return when (result.status) {
        CallResult.Status.OK -> CallOutcome.Returned((result.data ?: ByteArray(0)).toHex(), block, hash)
        CallResult.Status.REVERTED -> CallOutcome.Reverted(block, hash)
        CallResult.Status.REFUSED, CallResult.Status.INFEASIBLE -> CallOutcome.Refused
        CallResult.Status.UNAVAILABLE -> null
    }
}

/** A gas estimate: every status told apart, none dropped. */
internal fun estimateAnswer(result: EstimateResult): EstimateAnswer = when (result.status) {
    EstimateResult.Status.OK -> EstimateAnswer.Gas(result.gas)
    EstimateResult.Status.REVERTED -> EstimateAnswer.Reverted(result.revertData?.toHex().orEmpty())
    EstimateResult.Status.INFEASIBLE -> EstimateAnswer.Infeasible(result.detail.orEmpty())
    EstimateResult.Status.REFUSED -> EstimateAnswer.Refused(result.detail.orEmpty())
    EstimateResult.Status.UNAVAILABLE -> EstimateAnswer.Unavailable(result.detail)
}

/** A broadcast verdict; a sent transaction without its hash is no answer. */
internal fun sendAnswer(result: SendResult): SendAnswer = when (result.status) {
    SendResult.Status.SENT -> result.txHash?.let { SendAnswer.Sent("0x" + it.toHex()) }
        ?: SendAnswer.Unavailable("the engine sent no transaction hash")
    SendResult.Status.REJECTED -> SendAnswer.Rejected(result.detail.orEmpty())
    SendResult.Status.REFUSED -> SendAnswer.Refused(result.detail.orEmpty())
    SendResult.Status.UNAVAILABLE -> SendAnswer.Unavailable(result.detail)
}

/**
 * An ENS answer, an off-chain name told apart from a failure. The iOS module folds every
 * failure into the result's error, never a throw: no CCIP-Read gateway is configured, by
 * design, so an off-chain name is one of those errors.
 */
internal fun nameAnswer(result: EnsResolutionResult): NameAnswer = NameAnswer(
    addressHex = result.addressHex,
    blockNumber = result.blockNumber,
    verified = result.verified,
    error = result.error,
    offchain = result.error?.let(::isOffchainNameError) == true,
)

/**
 * The engine's advisory as the domain's [NetworkUpgrade]. Never throws: a field out of shape
 * becomes unknown rather than failing the status poll it arrives in (a time past
 * [NetworkUpgrade.LATEST_ACTIVATION_TIME], a fork id the domain would refuse). The API's
 * "unknown" values, a time of 0 and the fork id `0x00000000`, become null.
 */
internal fun UpgradeAdvisory.toNetworkUpgrade(): NetworkUpgrade = NetworkUpgrade(
    phase = when (phase) {
        UpgradePhase.SCHEDULED -> NetworkUpgrade.Phase.SCHEDULED
        UpgradePhase.ACTIVE -> NetworkUpgrade.Phase.ACTIVE
    },
    activationTime = activationTime.takeIf { it in 1..NetworkUpgrade.LATEST_ACTIVATION_TIME },
    forkId = forkId.lowercase().takeIf { NetworkUpgrade.isForkId(it) && it != UNKNOWN_FORK_ID },
    reportingNetworks = observedPeers.coerceAtLeast(0),
)

/** What the API reports for a fork id this build cannot compute (a blob-parameter-only fork). */
private const val UNKNOWN_FORK_ID = "0x00000000"
