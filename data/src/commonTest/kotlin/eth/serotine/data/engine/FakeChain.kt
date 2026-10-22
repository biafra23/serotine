package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.FinalizedBlock
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * A [VerifiedChain] whose snapshot and proofs a test sets. Each code, storage and head-storage
 * proof takes [proofTakes], added up in [spent]: the blocking calls' share of [clock].
 */
internal class FakeChain : VerifiedChain {
    var snapshot: EngineSnapshot = EngineSnapshot.Booting
    var proofTakes: Duration = 1.seconds
    var spent: Duration = Duration.ZERO
        private set

    /**
     * The time an adapter's budget is measured with, as in production: [scheduler]'s virtual time,
     * which the adapter's own pauses ([kotlinx.coroutines.delay]) move, plus what the proofs took.
     */
    fun clock(scheduler: TestCoroutineScheduler): TimeSource =
        object : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
            override fun read(): Long = scheduler.currentTime + spent.inWholeMilliseconds
        }
    val proofs = mutableMapOf<Address, ProvenBalance>()
    val codes = mutableMapOf<Address, ProvenCode>()
    val blocks = mutableMapOf<Long, String>()
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

    /**
     * The n-th code proof, verbatim (its anchor too, none included), when a test sets the proofs
     * apart from [codes]; [codeCalls] counts them.
     */
    var codeOfCall: ((Int) -> ProvenCode?)? = null
    var codeCalls = 0

    override fun proveCode(address: Address): ProvenCode? {
        failure?.let { throw it }
        spent += proofTakes
        proofRequests += address
        val call = codeCalls++
        codeOfCall?.let { return it(call) }
        // A code set without an anchor matched the attested slot of its block, as the slots' default does.
        return codes[address]?.let { it.copy(anchor = it.anchor ?: ProofAnchor.Attested(it.blockNumber)) }
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

    /** Whether the n-th storage proof asked for comes back null, when a test makes peers fail now and then. */
    var slotFailsOnCall: ((Int) -> Boolean)? = null

    /** What the n-th storage proof asked for throws (null: nothing), as an engine call across JNA can. */
    var slotThrowsOnCall: ((Int) -> Throwable?)? = null

    /** Called with each storage proof's number as it is asked for: a test cancels a read midway here. */
    var onSlotCall: ((Int) -> Unit)? = null

    /** The block of the n-th storage proof asked for, when a test moves the head under a read. */
    var slotBlockOfCall: ((Int) -> Long)? = null

    /**
     * The attested beacon slot the n-th storage proof matched, when a test sets it apart from its
     * block label; unset, a proof's anchor follows its block.
     */
    var slotAnchorOfCall: ((Int) -> Long)? = null

    /** When set, every storage proof is a header-chain walk of this block. */
    var slotWalked: Long? = null
    var slotCalls = 0

    override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? {
        failure?.let { throw it }
        spent += proofTakes
        val key = Triple(address, slot, holder)
        if (key in unprovenSlots) return null
        val call = slotCalls++
        onSlotCall?.invoke(call)
        slotThrowsOnCall?.invoke(call)?.let { throw it }
        if (slotFailsOnCall?.invoke(call) == true) return null
        val block = slotBlockOfCall?.invoke(call) ?: slotBlocks[key] ?: slotBlock
        val anchor = slotWalked?.let { ProofAnchor.Walked(it) }
            ?: ProofAnchor.Attested(slotAnchorOfCall?.invoke(call) ?: block)
        return ProvenSlot(slots[key] ?: "0".repeat(64), block, anchor)
    }

    override fun storageAt(address: Address, slot: ByteArray): String? {
        failure?.let { throw it }
        spent += proofTakes
        return storage[address to slot.toHex()] ?: "0".repeat(64)
    }

    override fun blockByNumber(number: Long): String? {
        failure?.let { throw it }
        return blocks[number]
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
    finalized: Long = 0,
    finalizedHeader: FinalizedBlock? = null,
) = EngineSnapshot.Running(
    phase, paused, start, current, target, hunting, proofPeers, head, headTimestamp, headAgeMs, bound, upgrade,
    finalized, finalizedHeader,
)
