package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeApprovals
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** How one owner of a Safe stands on a transaction (docs/wallet-ux.md, screen 17). */
sealed interface OwnerConfirmation {

    /** Its signature is in the request, and recovers to it over the hash computed here. */
    data object Signed : OwnerConfirmation

    /** It approved the hash on chain, as a verified call at [blockNumber] says (null: not said). */
    data class ApprovedOnChain(val blockNumber: Long?) : OwnerConfirmation

    /** Neither. */
    data object Missing : OwnerConfirmation

    /** No signature, and whether it approved on chain cannot be verified right now ([reason]). */
    data class Unknown(val reason: ReadUnavailable) : OwnerConfirmation
}

/** [owner], one of the Safe's current owners, whose key the wallet holds or not ([held]), and how it stands. */
data class SafeTxOwner(val owner: Address, val held: Boolean, val confirmation: OwnerConfirmation)

/**
 * Where a Safe transaction stands with the Safe's current owners (docs/wallet-ux.md, screen 17):
 * for each, as its verified storage lists them, whether it signed (a signature in the request
 * that recovers to it over the hash computed here), approved the hash on chain
 * ([SafeApprovals]), or neither. A signature of an address that is no owner now is not counted.
 * On [network] only.
 */
class ReadSafeTxOwners(
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val approvals: SafeApprovals,
    private val network: Network,
) {
    suspend operator fun invoke(request: SigningRequest): SafeTxOwnersReading = coroutineScope {
        if (request.network != network) {
            return@coroutineScope SafeTxOwnersReading.Refused(SafeTxRefusal.WrongChain(request.network.chainId))
        }
        val safe = when (val check = observeSafe.check(request.safe)) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return@coroutineScope SafeTxOwnersReading.Refused(check.refusal)
        }
        val signed = recovery.valid(request, safe.state.owners).map { it.owner }.toSet()
        val held = safe.ownersHeld.toSet()
        val owners = safe.state.owners.map { owner ->
            async {
                val confirmation = if (owner in signed) {
                    OwnerConfirmation.Signed
                } else {
                    when (val approval = approvals.approval(request.safe, owner, request.hash)) {
                        is ApprovalReading.Read -> if (approval.approved) {
                            OwnerConfirmation.ApprovedOnChain(approval.blockNumber)
                        } else {
                            OwnerConfirmation.Missing
                        }
                        is ApprovalReading.Unavailable -> OwnerConfirmation.Unknown(approval.reason)
                    }
                }
                SafeTxOwner(owner, owner in held, confirmation)
            }
        }.awaitAll()
        SafeTxOwnersReading.Read(owners, safe.state.threshold, safe.state.nonce, safe.blockNumber)
    }
}

/** The outcome of [ReadSafeTxOwners]. */
sealed interface SafeTxOwnersReading {

    /**
     * Every current [owners]' stand, in the Safe's order; its [threshold] and [safeNonce], verified
     * at [blockNumber].
     */
    data class Read(val owners: List<SafeTxOwner>, val threshold: Int, val safeNonce: Long, val blockNumber: Long) :
        SafeTxOwnersReading {
        /** How many owners confirm it, by signature or on chain. */
        val confirmations: Int
            get() = owners.count {
                it.confirmation == OwnerConfirmation.Signed || it.confirmation is OwnerConfirmation.ApprovedOnChain
            }
    }

    /** Not read: [refusal] (the Safe is none, or cannot be verified now). */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxOwnersReading
}

/**
 * An owner's on-chain approval of a Safe transaction, quoted for review: [request], whose hash
 * [owner] approves, and the transaction [owner] sends for it ([call]: `approveHash(hash)` to the
 * Safe, quoted as every transaction from an account this wallet holds is). Only
 * [QuoteSafeApproval] makes one.
 */
@ConsistentCopyVisibility
data class SafeApprovalQuote internal constructor(
    val request: SigningRequest,
    val owner: Address,
    val call: CallQuote,
)

/**
 * Quotes an owner's approval of a Safe transaction on chain (docs/wallet-ux.md, *Co-signing a
 * Safe transaction*: the alternative that needs no transport between devices): `approveHash` of
 * its hash, computed here, sent to the Safe by [owner], an account this wallet holds. Only a
 * current owner, as the Safe's verified storage says, approves, only a transaction whose nonce
 * the Safe has not passed, and not one it approved already ([SafeApprovals]); a delegate call is
 * refused. The approval is quoted as every transaction from an account this wallet holds is
 * ([CallQuoter]: the nonce behind its own transactions not mined yet, the fees, the gas, and
 * `approveHash` run as a verified call from [owner], which the Safe reverts for a non-owner),
 * and refused while [owner]'s approval of it sent before is not mined yet. On [network] only;
 * [clock] is in unix seconds.
 */
class QuoteSafeApproval(
    private val observeSafe: ObserveSafe,
    private val approvals: SafeApprovals,
    private val nativeBalances: NativeBalances,
    inputs: TransactionInputs,
    sentTransfers: SentTransfers,
    private val network: Network,
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    suspend operator fun invoke(request: SigningRequest, owner: Address, level: FeeLevel): SafeApprovalQuoting {
        fun refused(refusal: SafeTxRefusal) = SafeApprovalQuoting.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        if (request.tx.operation == SafeOperation.DELEGATECALL) return refused(SafeTxRefusal.DelegateCall)
        val state = when (val check = observeSafe.check(request.safe)) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        when (val approval = approvals.approval(request.safe, owner, request.hash)) {
            is ApprovalReading.Read -> if (approval.approved) return refused(SafeTxRefusal.AlreadyApproved)
            is ApprovalReading.Unavailable -> return refused(SafeTxRefusal.Unavailable(approval.reason))
        }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(owner).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return refused(SafeTxRefusal.NotQuoted(read.refusal))
        }
        val data = SafeCalls.approveHash(request.hash)
        return when (val quoting = quoter.quote(owner, request.safe, UInt256.ZERO, data, level, native)) {
            is CallQuoting.Quoted -> if (quoting.unmined.any { it.approves(request) }) {
                refused(SafeTxRefusal.AlreadySent)
            } else {
                SafeApprovalQuoting.Quoted(SafeApprovalQuote(request, owner, quoting.quote))
            }
            is CallQuoting.Refused -> refused(SafeTxRefusal.NotQuoted(quoting.refusal))
        }
    }
}

/** The outcome of [QuoteSafeApproval]. */
sealed interface SafeApprovalQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SafeApprovalQuote) : SafeApprovalQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeApprovalQuoting
}

/**
 * Sends a reviewed on-chain approval ([SafeApprovalQuote]): its transaction signed by the owner's
 * key as [unlock] opens it and broadcast, exactly as quoted, and recorded once a peer took it as
 * the approval of its request ([SentAction.SafeApproval]) — what keeps its nonce, and what its
 * status shows. As every transaction this wallet sends ([SendTransfer]); a retry goes through
 * [RebroadcastTransfer]. [clock] is in unix seconds.
 */
class ApproveSafeTxOnChain(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SafeApprovalQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.call,
        SentAction.SafeApproval(quote.request),
        clock,
        unlock,
    )
}

/**
 * A Safe transaction's execution, quoted for review: [request], the confirmations it is executed
 * with ([signatures], as many as the Safe's threshold, packed by owner into the call), and the
 * transaction [executor] sends for it ([call]: `execTransaction` to the Safe, quoted as every
 * transaction from an account this wallet holds is, its run the whole execution). Only
 * [QuoteSafeExecution] makes one.
 */
@ConsistentCopyVisibility
data class SafeExecutionQuote internal constructor(
    val request: SigningRequest,
    val executor: Address,
    val signatures: List<SafeSignature>,
    val call: CallQuote,
)

/**
 * Quotes a Safe transaction's execution (docs/wallet-ux.md, *Co-signing a Safe transaction*):
 * `execTransaction` sent to the Safe by [executor], an account this wallet holds, once its
 * owners' confirmations reach the threshold. Checked against the Safe's verified state first: a
 * delegate call is refused; its nonce must be the Safe's verified nonce (one below has run or
 * been replaced, one above waits for the transactions before it); and the confirmations are
 * counted from the Safe's current owners alone — each signature in the request that recovers to
 * its owner over the hash computed here, [executor]'s own when it is an owner (the Safe takes the
 * account executing it as confirming), and approvals on chain ([SafeApprovals]), read only when
 * the rest fall short. As many as the threshold are used, the executor's first.
 *
 * The execution is quoted as every transaction from an account this wallet holds is
 * ([CallQuoter]), and its run is the whole of it — the Safe's checks of the signatures and the
 * nonce, its guard, and the call itself — as a verified call from [executor] at the head, with no
 * state override: a revert, or an execution that returns false (a call that failed under a
 * non-zero gas price, which would use up the nonce), stops it here; so does an execution of it
 * [executor] sent before and no block has yet. The run has the engine's gas, not the limit the
 * execution is signed with, which is what the Safe's call gets when it has a `safeTxGas` and no
 * gas price: such a call could pass the run and fail on chain, so that one is refused
 * ([SafeTxRefusal.SafeTxGasUncheckable]). With neither, a call that fails reverts the execution,
 * and the estimate finds a limit it passes at; with a gas price, the call gets exactly its
 * `safeTxGas` whatever the limit. Any account the wallet holds can execute: the Safe asks
 * nothing of the sender but the gas. On [network] only; [clock] is in unix seconds.
 */
class QuoteSafeExecution(
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val approvals: SafeApprovals,
    private val nativeBalances: NativeBalances,
    inputs: TransactionInputs,
    sentTransfers: SentTransfers,
    private val network: Network,
    clock: () -> Long = ::epochSecondsNow,
) {
    private val quoter = CallQuoter(inputs, sentTransfers, network, clock)

    suspend operator fun invoke(request: SigningRequest, executor: Address, level: FeeLevel): SafeExecutionQuoting {
        fun refused(refusal: SafeTxRefusal) = SafeExecutionQuoting.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        if (request.tx.operation == SafeOperation.DELEGATECALL) return refused(SafeTxRefusal.DelegateCall)
        if (!request.tx.safeTxGas.isZero && request.tx.gasPrice.isZero) {
            return refused(SafeTxRefusal.SafeTxGasUncheckable)
        }
        val state = when (val check = observeSafe.check(request.safe)) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (request.nonce > state.nonce) return refused(SafeTxRefusal.NotNext(state.nonce))
        val signatures = when (val confirmed = confirmations(request, state, executor)) {
            is Confirmed.Enough -> confirmed.signatures
            is Confirmed.Refused -> return refused(confirmed.refusal)
        }
        val native = when (val read = NativeRead.of(nativeBalances.observeBalance(executor).first())) {
            is NativeRead.Before -> read
            is NativeRead.NotRead -> return refused(SafeTxRefusal.NotQuoted(read.refusal))
        }
        val data = SafeCalls.execTransaction(request.tx, signatures)
        val quoting = quoter.quote(executor, request.safe, UInt256.ZERO, data, level, native) { it == TRUE }
        return when (quoting) {
            // Run at the head, a second execution passes while the first is on its way, and
            // reverts once that is mined.
            is CallQuoting.Quoted -> if (quoting.unmined.any { it.executes(request) }) {
                refused(SafeTxRefusal.AlreadySent)
            } else {
                SafeExecutionQuoting.Quoted(SafeExecutionQuote(request, executor, signatures, quoting.quote))
            }
            is CallQuoting.Refused -> refused(SafeTxRefusal.NotQuoted(quoting.refusal))
        }
    }

    /** As many confirmations of [request] by [state]'s owners as its threshold, or why there are not. */
    private suspend fun confirmations(request: SigningRequest, state: SafeState, executor: Address): Confirmed =
        coroutineScope {
            val threshold = state.threshold
            val signed = recovery.valid(request, state.owners)
            // The executor's own confirmation, when it is an owner that has not signed: the Safe
            // takes the account sending execTransaction as having approved it.
            val ownApproval = executor in state.owners && signed.none { it.owner == executor }
            val confirmed = listOfNotNull(SafeSignature.ApprovedHash(executor).takeIf { ownApproval }) + signed
            if (confirmed.size >= threshold) return@coroutineScope Confirmed.Enough(confirmed.take(threshold))
            val others = state.owners.filter { owner -> confirmed.none { it.owner == owner } }
            val readings = others
                .map { owner -> async { owner to approvals.approval(request.safe, owner, request.hash) } }
                .awaitAll()
            val approved = readings.filter { (_, reading) -> (reading as? ApprovalReading.Read)?.approved == true }
                .map { (owner, _) -> SafeSignature.ApprovedHash(owner) }
            val all = confirmed + approved
            if (all.size >= threshold) return@coroutineScope Confirmed.Enough(all.take(threshold))
            // Short: an approval that could not be read might have made up the difference.
            readings.firstNotNullOfOrNull { (_, reading) -> reading as? ApprovalReading.Unavailable }?.let {
                return@coroutineScope Confirmed.Refused(SafeTxRefusal.Unavailable(it.reason))
            }
            Confirmed.Refused(SafeTxRefusal.ThresholdNotMet(all.size, threshold))
        }

    private sealed interface Confirmed {
        data class Enough(val signatures: List<SafeSignature>) : Confirmed
        data class Refused(val refusal: SafeTxRefusal) : Confirmed
    }

    private companion object {
        /** What `execTransaction` returns when the Safe's call succeeded: `true`, one ABI word. */
        val TRUE = "0".repeat(63) + "1"
    }
}

/** Whether this transaction is an on-chain approval of [request]'s Safe transaction. */
private fun SentTransfer.approves(request: SigningRequest): Boolean =
    (action as? SentAction.SafeApproval)?.request?.hash == request.hash

/** Whether this transaction is an execution of [request]'s Safe transaction. */
private fun SentTransfer.executes(request: SigningRequest): Boolean =
    (action as? SentAction.SafeExecution)?.request?.hash == request.hash

/** The outcome of [QuoteSafeExecution]. */
sealed interface SafeExecutionQuoting {

    /** [quote] is ready for review. */
    data class Quoted(val quote: SafeExecutionQuote) : SafeExecutionQuoting

    /** Not quoted: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeExecutionQuoting
}

/**
 * Sends a reviewed execution ([SafeExecutionQuote]): its transaction signed by the executor's
 * key as [unlock] opens it and broadcast, exactly as quoted, and recorded once a peer took it as
 * the execution of its request ([SentAction.SafeExecution]) — what keeps its nonce, and what its
 * status is followed by ([ObserveSentTransfer]: its record and its verified receipt, as a send's;
 * docs/wallet-ux.md, screen 9). As every transaction this wallet sends ([SendTransfer]); a retry
 * goes through [RebroadcastTransfer]. [clock] is in unix seconds.
 */
class ExecuteSafeTx(
    private val signer: Signer,
    private val broadcaster: Broadcaster,
    private val sentTransfers: SentTransfers,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(quote: SafeExecutionQuote, unlock: Unlock): TransferSending = signAndSend(
        signer,
        broadcaster,
        sentTransfers,
        quote.call,
        SentAction.SafeExecution(quote.request),
        clock,
        unlock,
    )
}
