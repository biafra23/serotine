package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.NetworkUpgrade

/** A [VerifiedChain] whose snapshot and proofs a test sets. */
internal class FakeChain : VerifiedChain {
    var snapshot: EngineSnapshot = EngineSnapshot.Booting
    val proofs = mutableMapOf<Address, ProvenBalance>()
    val proofRequests = mutableListOf<Address>()
    var failure: Throwable? = null
    var snapshots = 0

    override fun snapshot(): EngineSnapshot {
        snapshots++
        failure?.let { throw it }
        return snapshot
    }

    override fun proveBalance(address: Address): ProvenBalance? {
        failure?.let { throw it }
        proofRequests += address
        return proofs[address]
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
    upgrade: NetworkUpgrade? = null,
) = EngineSnapshot.Running(
    phase, paused, start, current, target, hunting, proofPeers, head, headTimestamp, headAgeMs, bound, upgrade,
)
