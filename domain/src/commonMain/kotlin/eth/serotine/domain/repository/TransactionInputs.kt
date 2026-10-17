package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.UInt256

/**
 * The port for what a transaction needs from the chain before it is signed: the sender's next
 * nonce, the fees the last blocks paid, the gas it takes, and how it runs — every answer from
 * the embedded light client's verified state (CLAUDE.md, *Trust*). :data implements it over the
 * Myotis engine; nothing else may answer it. Every call blocks on the engine, bounded by it,
 * and never throws.
 */
interface TransactionInputs {

    /**
     * The nonce [account]'s next transaction must carry: its verified transaction count,
     * counting the transactions this device sent that are not mined yet.
     */
    suspend fun nonce(account: Address): NonceReading

    /** The fee levels the verified fee history of the last blocks suggests. */
    suspend fun fees(): FeeReading

    /**
     * The gas a transaction from [from] to [to] carrying [value] and [data] takes, as the
     * engine's own EVM runs it over verified state at the head, with its safety buffer.
     */
    suspend fun estimateGas(from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate

    /**
     * The same transaction run as a verified call from [from] against the head: what the
     * review shows before anything is signed, and a revert stops there.
     */
    suspend fun simulate(from: Address, to: Address, value: UInt256, data: ByteArray): Simulation
}

/** The outcome of [TransactionInputs.nonce]. */
sealed interface NonceReading {

    /** [nonce] is the next one. */
    data class Read(val nonce: Long) : NonceReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : NonceReading
}

/** The outcome of [TransactionInputs.fees]. */
sealed interface FeeReading {

    /** [quote] is what the fee history suggests. */
    data class Quoted(val quote: FeeQuote) : FeeReading

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : FeeReading
}

/**
 * Fees from the verified fee history of the blocks up to [blockNumber]: [nextBaseFee] is the
 * base fee the next block will charge per gas, and [tips] the priority fee per gas each
 * [FeeLevel] offers.
 */
data class FeeQuote(val nextBaseFee: UInt256, val tips: Map<FeeLevel, UInt256>, val blockNumber: Long) {
    init {
        require(tips.keys == FeeLevel.entries.toSet()) { "a tip for every level" }
    }

    /**
     * The fee cap per gas for [level]: twice the next base fee, room for it to rise for
     * several full blocks, plus the level's tip. Only what the block charges is paid.
     */
    fun maxFeePerGas(level: FeeLevel): UInt256 =
        requireNotNull(maxFeePerGasOrNull(level)) { "a fee cap above 2^256 - 1" }

    /** [maxFeePerGas], or null when it would not fit in 256 bits (an answer no chain gives). */
    fun maxFeePerGasOrNull(level: FeeLevel): UInt256? =
        nextBaseFee.plusOrNull(nextBaseFee)?.plusOrNull(tip(level))

    /** The priority fee per gas [level] offers. */
    fun tip(level: FeeLevel): UInt256 = tips.getValue(level)
}

/** How fast a transaction should be mined, as its tip offers. */
enum class FeeLevel { SLOW, NORMAL, FAST }

/** The outcome of [TransactionInputs.estimateGas]. */
sealed interface GasEstimate {

    /** It takes [gas], the engine's buffered estimate. */
    data class Estimated(val gas: Long) : GasEstimate

    /** It reverts, with [revertHex] (lower-case hex, no `0x`, possibly empty): a verified answer. */
    data class Reverted(val revertHex: String) : GasEstimate

    /** It cannot succeed within what the sender can pay ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : GasEstimate

    /** The engine will not estimate it on this build ([detail]). */
    data class Refused(val detail: String) : GasEstimate

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : GasEstimate
}

/** The outcome of [TransactionInputs.simulate]. */
sealed interface Simulation {

    /** It ran and returned [returnHex] (lower-case, no `0x`) against the state of [blockNumber]. */
    data class Succeeded(val returnHex: String, val blockNumber: Long?) : Simulation

    /** It ran and reverted, against the state of [blockNumber]: a verified answer. */
    data class Reverted(val blockNumber: Long?) : Simulation

    /** The engine will not run it on this build. */
    data object Refused : Simulation

    /** No verified answer right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : Simulation
}
