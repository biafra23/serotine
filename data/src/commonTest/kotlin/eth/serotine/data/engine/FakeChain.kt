package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address

/** A [VerifiedChain] whose snapshot and proofs a test sets. */
internal class FakeChain : VerifiedChain {
    var snapshot: EngineSnapshot = EngineSnapshot.Booting
    val proofs = mutableMapOf<Address, ProvenBalance>()
    val codes = mutableMapOf<Address, ProvenCode>()
    val proofRequests = mutableListOf<Address>()
    var failure: Exception? = null

    override fun snapshot(): EngineSnapshot {
        failure?.let { throw it }
        return snapshot
    }

    override fun proveBalance(address: Address): ProvenBalance? {
        failure?.let { throw it }
        proofRequests += address
        return proofs[address]
    }

    override fun proveCode(address: Address): ProvenCode? {
        failure?.let { throw it }
        proofRequests += address
        return codes[address]
    }

    /** Answers per contract and calldata (hex), as a test sets them. */
    val calls = mutableMapOf<Pair<Address, String>, ProvenCall>()

    override fun call(to: Address, data: ByteArray): ProvenCall? {
        failure?.let { throw it }
        return calls[to to data.toHex()]
    }
}

/** A running engine's snapshot; by default synced, awake, fresh and with proof peers. */
internal fun running(
    phase: Phase = Phase.SYNCED,
    paused: Boolean = false,
    start: Long = -1,
    current: Long = 0,
    target: Long = 0,
    hunting: Boolean = false,
    proofPeers: Int = 2,
    head: Long = 100,
    headTimestamp: Long? = null,
    headAgeMs: Long = 2_000,
    bound: Long = 13,
) = EngineSnapshot.Running(
    phase, paused, start, current, target, hunting, proofPeers, head, headTimestamp, headAgeMs, bound,
)
