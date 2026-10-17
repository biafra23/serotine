package eth.serotine.data.engine

import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * What the engine adapters need from a verified light client: a [snapshot] of its state,
 * which never blocks, and the proofs ([proveBalance], [proveCode]), which do (they fetch a
 * proof from a peer) and so run on the adapters' dispatcher, never a caller's thread.
 */
internal interface VerifiedChain {

    /** The engine's state right now (`ChainHandle.status()`). Never blocks. */
    fun snapshot(): EngineSnapshot

    /**
     * The balance of [address], proven against the light client's beacon-attested state;
     * null when the engine could not prove it. Blocks, bounded by the engine (about 90 s).
     */
    fun proveBalance(address: Address): ProvenBalance?

    /**
     * The code at [address] — none for an account without code or one that does not exist —
     * proven against the light client's beacon-attested state; null when the engine could
     * not prove it. Blocks, bounded by the engine.
     */
    fun proveCode(address: Address): ProvenCode?

    /**
     * [data] called on the contract at [to], from no sender, run against the light client's
     * beacon-attested state; null when the engine could not answer verified right now.
     * Blocks, bounded by the engine.
     */
    fun call(to: Address, data: ByteArray): CallOutcome?
}

/** A verified call's answer. */
internal sealed interface CallOutcome {

    /** It ran and returned [hex] (lower-case, no `0x`), against the state of [blockNumber] (null: not said). */
    data class Returned(val hex: String, val blockNumber: Long?) : CallOutcome

    /** It ran and reverted: a verified answer too. */
    data class Reverted(val blockNumber: Long?) : CallOutcome

    /** The engine will not answer it on this build, and asking again changes nothing. */
    data object Refused : CallOutcome
}

/** A balance in decimal wei, proven at [blockNumber], the block the proof anchors to. */
internal data class ProvenBalance(val wei: String, val blockNumber: Long)

/** Code as lower-case hex without `0x` (empty: none), proven at [blockNumber]. */
internal data class ProvenCode(val codeHex: String, val blockNumber: Long)

/** Keccak-256 of no code: the code hash of every account without code. */
internal const val EMPTY_CODE_HASH = "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

/**
 * The code of an account whose proof (verified at [blockNumber]) says it [exists] with the
 * code hash [codeHashHex]: none for an absent account or the empty hash; otherwise what
 * [fetchCode] returns, accepted only when it hashes to the proven value — the head may have
 * moved between the proof and the read, and code from another block must not carry this
 * one's label. Null, unproven, when the hash is missing or the code is not the proven code.
 */
internal fun provenCode(
    exists: Boolean,
    codeHashHex: String?,
    blockNumber: Long,
    fetchCode: () -> ByteArray?,
): ProvenCode? {
    if (!exists) return ProvenCode("", blockNumber)
    val codeHash = codeHashHex?.removePrefix("0x")?.lowercase() ?: return null
    if (codeHash == EMPTY_CODE_HASH) return ProvenCode("", blockNumber)
    val code = fetchCode() ?: return null
    if (Keccak256().digest(code).toHex() != codeHash) return null
    return ProvenCode(code.toHex(), blockNumber)
}

/** The engine's state, or why there is none. */
internal sealed interface EngineSnapshot {

    /** The engine is still starting. */
    data object Booting : EngineSnapshot

    /** There is no engine, or it failed to start; this is final. */
    data object Down : EngineSnapshot

    /** The engine's report; see `io.myotis.api.StatusSnapshot` for each field. */
    data class Running(
        val phase: Phase,
        val paused: Boolean,
        val syncStartPeriod: Long,
        val syncCurrentPeriod: Long,
        val syncTargetPeriod: Long,
        val huntingLightClientServers: Boolean,
        val proofPeers: Int,
        val headBlock: Long,
        val headTimestamp: Long?,
        val headAgeMs: Long,
        val wsBoundPeriods: Long,
    ) : EngineSnapshot {

        /**
         * The head reads can be verified at, or null when none can: synced, awake, with a
         * verified head younger than [FRESH_HEAD_MS], and a peer that serves state proofs.
         * The same gate Myotis's own readiness ladder applies (its `READY_HEAD_WARM_MS`).
         */
        val readableHead: Long?
            get() = headBlock.takeIf {
                phase == Phase.SYNCED && !paused && headAgeMs <= FRESH_HEAD_MS && proofPeers > 0 && it > 0
            }
    }

    /** `io.myotis.api.BeaconState`. */
    enum class Phase { STARTING, SYNCING, CATCHING_UP, SYNCED, STALE_ANCHOR }

    companion object {
        /** A verified head older than this is stale: reads wait for a fresh one. */
        const val FRESH_HEAD_MS = 45_000L
    }
}
