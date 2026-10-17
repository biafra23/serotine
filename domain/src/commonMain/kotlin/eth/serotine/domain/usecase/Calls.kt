package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransferList
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A transaction from an account this wallet holds, quoted for review: the unsigned
 * [transaction] at [level]'s fees, the most its fee can be ([maxFee], gas limit × fee cap), and
 * the native balance it leaves — [nativeBefore], what this wallet's own transactions not mined
 * yet may still take of it ([pendingCost]), and the least it can be after
 * ([nativeAfterAtLeast]). Every figure comes from verified state, each at its own block: the
 * balance at [balanceBlock], the fees from the history up to [feeBlock], and the transaction run
 * as a verified call from [from] at [simulationBlock].
 *
 * What a transfer quote ([TransferQuote]) and a Safe transaction's approval or execution
 * ([SafeApprovalQuote], [SafeExecutionQuote]) are built on. Only this module makes one.
 */
@ConsistentCopyVisibility
data class CallQuote internal constructor(
    val from: Address,
    val level: FeeLevel,
    val transaction: Eip1559Transaction,
    val maxFee: UInt256,
    val nativeBefore: UInt256,
    val pendingCost: UInt256,
    val nativeAfterAtLeast: UInt256,
    val balanceBlock: Long?,
    val feeBlock: Long,
    val simulationBlock: Long?,
) {
    /** The most the transaction can take of the account's native balance: its value and its fee. */
    val maxCost: UInt256
        get() = maxFee.plusOrNull(transaction.value) ?: maxFee
}

/** Why a transaction from an account this wallet holds was not quoted; nothing is signed. */
sealed interface CallRefusal {

    /** The sender's balance of the network's currency cannot pay the value and the most the fee can be. */
    data object InsufficientFunds : CallRefusal

    /** It cannot succeed for a reason other than the sender's funds ([detail], the EVM's words). */
    data class Infeasible(val detail: String) : CallRefusal

    /** It reverts, run as a verified call from the sender (at [blockNumber]). */
    data class Reverts(val blockNumber: Long?) : CallRefusal

    /** The engine will not estimate or run it on this build. */
    data object Refused : CallRefusal

    /** The light client has no verified head yet. */
    data object Syncing : CallRefusal

    /** A verified figure could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : CallRefusal
}

/** A verified native balance a quote starts from, or why there is none. */
internal sealed interface NativeRead {

    /** [wei] at [blockNumber]. */
    data class Before(val wei: UInt256, val blockNumber: Long) : NativeRead

    /** No balance to quote on: [refusal]. */
    data class NotRead(val refusal: CallRefusal) : NativeRead

    companion object {
        /** What [reading] gives a quote. */
        fun of(reading: BalanceReading): NativeRead = when (reading) {
            is BalanceReading.Verified -> reading.wei.toUInt256OrNull()?.let { Before(it, reading.blockNumber) }
                ?: NotRead(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN))
            BalanceReading.Syncing -> NotRead(CallRefusal.Syncing)
            is BalanceReading.Unavailable -> NotRead(CallRefusal.Unavailable(reading.reason))
        }
    }
}

/** What [CallQuoter.quote] gives. */
internal sealed interface CallQuoting {

    /** [quote], and the sender's own transactions not mined yet it was quoted behind ([unmined]). */
    data class Quoted(val quote: CallQuote, val unmined: List<SentTransfer>) : CallQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: CallRefusal) : CallQuoting
}

/**
 * The quote of any transaction from an account this wallet holds, the part every kind of
 * transaction shares: the sender's next nonce, the fees at a [FeeLevel], the gas it takes, the
 * transaction run as a verified call from the sender, which must not revert, and the native
 * balance it needs. Nothing is quoted on anything less than verified state. On [network] only,
 * its chain id signed into the transaction.
 *
 * The sender's own transactions a peer took and no block has yet ([SentTransfers]) count: the
 * nonce is never below the last of them plus one, and what they may still take of the native
 * balance is not available to this one. Those mined since are forgotten, and so are those a
 * peer took more than [RECORD_LIFETIME_SECONDS] ago and still not mined: the network's
 * transaction pools have dropped them by then (geth's default lifetime), and kept they would
 * hold every later transaction behind a nonce no block takes. Only one transaction per nonce can
 * be mined, so taking such a nonce again can never pay twice. [clock] is in unix seconds.
 */
internal class CallQuoter(
    private val inputs: TransactionInputs,
    private val sentTransfers: SentTransfers,
    private val network: Network,
    private val clock: () -> Long,
) {

    /**
     * [value] wei and [data] from [from] to [to] at [level]'s fees, on [native], the sender's
     * verified balance read before. [accepts] judges what the run returned: false is a revert
     * (a token's `transfer` that returns false refused as surely as one that reverts).
     */
    suspend fun quote(
        from: Address,
        to: Address,
        value: UInt256,
        data: ByteArray,
        level: FeeLevel,
        native: NativeRead.Before,
        accepts: (returnHex: String) -> Boolean = { true },
    ): CallQuoting = coroutineScope {
        fun refused(refusal: CallRefusal) = CallQuoting.Refused(refusal)

        // The nonces and the fee history, alongside the estimate and the run.
        val nonce = async { inputs.nonce(from) }
        val mined = async { inputs.minedNonce(from) }
        val recorded = async { sentTransfers.observe().first() }
        val fees = async { inputs.fees() }

        val gas = when (val estimate = inputs.estimateGas(from, to, value, data)) {
            // The engine's, priced under the fork the chain runs: on Sepolia's Amsterdam a plain
            // transfer takes less than the 21000 it did before (EIP-2780), so no floor is added.
            is GasEstimate.Estimated -> estimate.gas
            is GasEstimate.Reverted -> return@coroutineScope refused(CallRefusal.Reverts(blockNumber = null))
            // The EVM's words: about what the sender can pay, or something else it cannot do.
            is GasEstimate.Infeasible -> return@coroutineScope refused(
                if (aboutFunds(estimate.detail)) {
                    CallRefusal.InsufficientFunds
                } else {
                    CallRefusal.Infeasible(estimate.detail)
                },
            )
            is GasEstimate.Refused -> return@coroutineScope refused(CallRefusal.Refused)
            is GasEstimate.Unavailable -> return@coroutineScope refused(CallRefusal.Unavailable(estimate.reason))
        }
        val simulationBlock = when (val simulation = inputs.simulate(from, to, value, data)) {
            is Simulation.Succeeded -> if (accepts(simulation.returnHex)) {
                simulation.blockNumber
            } else {
                return@coroutineScope refused(CallRefusal.Reverts(simulation.blockNumber))
            }
            is Simulation.Reverted -> return@coroutineScope refused(CallRefusal.Reverts(simulation.blockNumber))
            Simulation.Refused -> return@coroutineScope refused(CallRefusal.Refused)
            is Simulation.Unavailable -> return@coroutineScope refused(CallRefusal.Unavailable(simulation.reason))
        }
        val quote = when (val reading = fees.await()) {
            is FeeReading.Quoted -> reading.quote
            is FeeReading.Unavailable -> return@coroutineScope refused(CallRefusal.Unavailable(reading.reason))
        }
        val pendingNonce = when (val reading = nonce.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable -> return@coroutineScope refused(CallRefusal.Unavailable(reading.reason))
        }
        val minedCount = when (val reading = mined.await()) {
            is NonceReading.Read -> reading.nonce
            is NonceReading.Unavailable -> return@coroutineScope refused(CallRefusal.Unavailable(reading.reason))
        }
        // This wallet's own sends not mined yet: a record that cannot be read leaves the engine's.
        val expiredBefore = clock() - RECORD_LIFETIME_SECONDS
        val unmined = (recorded.await() as? SentTransferList.Loaded)?.transfers.orEmpty()
            .filter { it.account == from && it.nonce >= minedCount && it.sentAt >= expiredBefore }
        // Never below the mined count either: the two counts may come from two heads.
        val next = maxOf(pendingNonce, minedCount, (unmined.maxOfOrNull { it.nonce } ?: -1) + 1)
        sentTransfers.prune(from, minedCount, expiredBefore)
        val insufficient = refused(CallRefusal.InsufficientFunds)
        val pendingCost = unmined.map { it.maxCost }.sumOrNull() ?: return@coroutineScope insufficient
        val available = native.wei.minusOrNull(pendingCost) ?: return@coroutineScope insufficient
        // An engine answer this large is no answer: the use case never throws over it.
        val unproven = refused(CallRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        val maxFeePerGas = quote.maxFeePerGasOrNull(level) ?: return@coroutineScope unproven
        val maxFee = maxFeePerGas.timesOrNull(UInt256.of(gas)) ?: return@coroutineScope unproven
        val cost = maxFee.plusOrNull(value) ?: return@coroutineScope insufficient
        if (available < cost) return@coroutineScope insufficient
        CallQuoting.Quoted(
            CallQuote(
                from = from,
                level = level,
                transaction = Eip1559Transaction(
                    chainId = network.chainId,
                    nonce = next,
                    maxPriorityFeePerGas = quote.tip(level),
                    maxFeePerGas = maxFeePerGas,
                    gasLimit = gas,
                    to = to,
                    value = value,
                    data = data,
                ),
                maxFee = maxFee,
                nativeBefore = native.wei,
                pendingCost = pendingCost,
                nativeAfterAtLeast = available - cost,
                balanceBlock = native.blockNumber,
                feeBlock = quote.blockNumber,
                simulationBlock = simulationBlock,
            ),
            unmined,
        )
    }
}

/**
 * How long a recorded transaction that no block took holds its nonce: three hours, the lifetime
 * geth's transaction pool gives a transaction before it drops it.
 */
const val RECORD_LIFETIME_SECONDS: Long = 3 * 60 * 60

/** The sum of these numbers, or null when it overflows 256 bits. */
internal fun Iterable<UInt256>.sumOrNull(): UInt256? = fold(UInt256.ZERO as UInt256?) { sum, it -> sum?.plusOrNull(it) }

/** Whether an infeasible estimate's [detail] (geth's words) is about the sender's funds. */
private fun aboutFunds(detail: String): Boolean =
    "insufficient funds" in detail || "exceeds allowance" in detail

/**
 * Signs [quote]'s transaction with its sender's key as [unlock] opens it ([Signer]) and hands it
 * to the network: what every transaction this wallet sends goes through. What is signed is
 * exactly what was quoted. Once a peer took it, it is recorded as doing [action] ([SentTransfers]):
 * what keeps its nonce from a later transaction after a restart, and what its status shows. One
 * no peer took is not recorded, so it holds no nonce: a later quote may take the same one, and
 * only one of the two can ever be mined. The broadcast and the record are one step a
 * cancellation cannot cut in half; a record that cannot be saved does not undo the send.
 */
internal suspend fun signAndSend(
    signer: Signer,
    broadcaster: Broadcaster,
    sentTransfers: SentTransfers,
    quote: CallQuote,
    action: SentAction,
    clock: () -> Long,
    unlock: Unlock,
): TransferSending {
    val transaction = quote.transaction
    val signature = when (val signed = signer.sign(quote.from, transaction.signingDigest(), unlock)) {
        is Signing.Signed -> signed.signature
        else -> return TransferSending.NotSigned(signed)
    }
    val transfer = SentTransfer(
        account = quote.from,
        nonce = transaction.nonce,
        signed = SignedTransfer(transaction.encodeSigned(signature)),
        action = action,
        maxCost = quote.maxCost,
        sentAt = clock(), // restamped once a peer took it
    )
    return withContext(NonCancellable) {
        when (val broadcast = broadcaster.send(transfer.signed.raw)) {
            is Broadcast.Sent -> sent(sentTransfers, transfer, clock)
            else -> TransferSending.NotSent(broadcast, transfer)
        }
    }
}

/**
 * [transfer], which a peer took: recorded with the moment it was taken ([clock], unix seconds),
 * which its lifetime in the network's pools runs from, and [TransferSending.Sent] by its hash.
 */
internal suspend fun sent(sentTransfers: SentTransfers, transfer: SentTransfer, clock: () -> Long): TransferSending {
    sentTransfers.record(transfer.copy(sentAt = clock()))
    // The hash is the raw bytes' own: what the receipt is looked up by.
    return TransferSending.Sent(transfer.hash)
}
