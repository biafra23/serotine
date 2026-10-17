package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.ReadUnavailable

/**
 * What the transaction adapters need beyond [VerifiedChain]: the sender's nonce, the fee
 * history, a gas estimate and a call from a sender, the broadcast, a receipt and an ENS name,
 * each answered by the engine's verified reads (`io.myotis.api.VerifiedReads`, `EnsApi`). Every
 * call blocks, bounded by the engine; none throws on a missing answer (it says so), but the
 * engine's own failures cross as exceptions the adapters catch.
 */
internal interface TransactingChain : VerifiedChain {

    /**
     * The nonce of [address] at `pending`: its verified count plus this node's own pending
     * sends; null: no answer.
     */
    fun pendingNonce(address: Address): Long?

    /** `eth_feeHistory` over the last [blocks] blocks up to `latest`, with [percentiles]: its JSON, or null. */
    fun feeHistory(blocks: Int, percentiles: DoubleArray): String?

    /** A gas estimate of the transaction from [from] to [to] with [valueWei] and [data], at the head. */
    fun estimateGas(from: Address, to: Address, valueWei: String, data: ByteArray): EstimateAnswer

    /** The same transaction as a verified call from [from] at the head; null: no verified answer. */
    fun callFrom(from: Address, to: Address, valueWei: String, data: ByteArray): CallOutcome?

    /** Gossips the signed [raw] transaction. */
    fun sendRaw(raw: ByteArray): SendAnswer

    /**
     * The receipt of [hash] (`0x` + 64 hex): its JSON, verified against its block's receipts
     * root; the "null" literal when no verified block includes it; null: no answer.
     */
    fun receipt(hash: String): String?

    /** [name]'s address, resolved against beacon-finalized state. */
    fun resolveName(name: String): NameAnswer
}

/** An engine's gas estimate. */
internal sealed interface EstimateAnswer {
    data class Gas(val gas: Long) : EstimateAnswer
    data class Reverted(val revertHex: String) : EstimateAnswer
    data class Infeasible(val detail: String) : EstimateAnswer
    data class Refused(val detail: String) : EstimateAnswer
    data object Unavailable : EstimateAnswer
}

/** An engine's broadcast verdict. */
internal sealed interface SendAnswer {
    data class Sent(val hashHex: String) : SendAnswer
    data class Rejected(val detail: String) : SendAnswer
    data class Refused(val detail: String) : SendAnswer
    data class Unavailable(val detail: String?) : SendAnswer
}

/**
 * An engine's ENS answer: [addressHex] null with [error] null is a verified "no address";
 * [verified] says the resolution ran against beacon-finalized state, at [blockNumber].
 */
internal data class NameAnswer(
    val addressHex: String?,
    val blockNumber: Long,
    val verified: Boolean,
    val error: String?,
)

/** Why reads cannot be verified now, from [snapshot], or null when they can (at its readable head). */
internal fun whyUnreadable(snapshot: EngineSnapshot): ReadUnavailable? = when (snapshot) {
    EngineSnapshot.Down -> ReadUnavailable.NO_ENGINE
    EngineSnapshot.Booting -> ReadUnavailable.UNPROVEN
    is EngineSnapshot.Running -> when {
        snapshot.phase == EngineSnapshot.Phase.STALE_ANCHOR -> ReadUnavailable.STALE_ANCHOR
        snapshot.readableHead == null -> ReadUnavailable.UNPROVEN
        else -> null
    }
}
