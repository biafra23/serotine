package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import io.myotis.api.CallResult

/**
 * A Myotis call result as the adapters read it. `callDetailed` tells a revert (a verified
 * answer) from no answer and names the block the call ran against when it can (-1 when it
 * does not), with its hash when the engine proved it. OK and REVERTED are answers. REFUSED is a permanent refusal, and so is
 * INFEASIBLE for a plain call, whose calldata alone outran the engine's budget. UNAVAILABLE
 * is no verified answer right now.
 */
internal fun callOutcome(result: CallResult): CallOutcome? {
    val block = result.blockNumber().takeIf { it >= 0 }
    val hash = result.blockHashHex()?.lowercase()
    return when (result.status()) {
        CallResult.Status.OK -> CallOutcome.Returned((result.data() ?: ByteArray(0)).toHex(), block, hash)
        CallResult.Status.REVERTED -> CallOutcome.Reverted(block, hash)
        CallResult.Status.REFUSED, CallResult.Status.INFEASIBLE -> CallOutcome.Refused
        CallResult.Status.UNAVAILABLE, null -> null
    }
}
