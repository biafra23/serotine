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
    val calls = mutableMapOf<Pair<Address, String>, CallOutcome>()

    override fun call(to: Address, data: ByteArray): CallOutcome? {
        failure?.let { throw it }
        return calls[to to data.toHex()]
    }

    /** Proven storage words per contract, slot and mapping key, as a test sets them; unset: proven empty. */
    val slots = mutableMapOf<Triple<Address, Long, Address?>, String>()

    /** The block every storage proof is labelled with, or per slot in [slotBlocks]. */
    var slotBlock = 9L
    val slotBlocks = mutableMapOf<Triple<Address, Long, Address?>, Long>()

    /** Storage words per contract and 32-byte slot (hex), as a test sets them; unset: empty. */
    val storage = mutableMapOf<Pair<Address, String>, String>()

    /** Slots no proof is had for: unproven. */
    val unprovenSlots = mutableSetOf<Triple<Address, Long, Address?>>()

    /** The block of the n-th storage proof asked for, when a test moves the head under a read. */
    var slotBlockOfCall: ((Int) -> Long)? = null
    var slotCalls = 0

    override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
        failure?.let { throw it }
        val key = Triple(address, slot, holder)
        if (key in unprovenSlots) return null
        val block = slotBlockOfCall?.invoke(slotCalls++) ?: slotBlocks[key] ?: slotBlock
        return ProvenSlot(slots[key] ?: "0".repeat(64), block)
    }

    override fun storageAt(address: Address, slot: ByteArray): String? {
        failure?.let { throw it }
        return storage[address to slot.toHex()] ?: "0".repeat(64)
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
