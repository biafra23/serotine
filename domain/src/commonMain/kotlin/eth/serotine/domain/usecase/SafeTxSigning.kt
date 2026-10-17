package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeTxQueue
import eth.serotine.domain.repository.SafeTxQueueList
import eth.serotine.domain.repository.SignatureRecovery
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.SigningRequestCodec
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Why a Safe transaction was not imported, signed, approved or executed (docs/wallet-ux.md,
 * screen 17): each use case says which of these it answers. Nothing was signed or sent.
 */
sealed interface SafeTxRefusal {

    /** The text is no signing request ([SigningRequestDecoding.Malformed]). */
    data object Malformed : SafeTxRefusal

    /** A signing request of [version], which this build does not read. */
    data class UnknownVersion(val version: Int) : SafeTxRefusal

    /** A request for the chain [chainId], not the network the app works on. */
    data class WrongChain(val chainId: Long) : SafeTxRefusal

    /** The address holds no Safe this app recognises, as its verified code and storage say. */
    data object NotASafe : SafeTxRefusal

    /**
     * The transaction's nonce is below the Safe's verified nonce, [safeNonce]: that nonce is used
     * on chain, by this transaction or another, so it can never run.
     */
    data class NonceUsed(val safeNonce: Long) : SafeTxRefusal

    /**
     * The transaction's nonce is above the Safe's verified nonce, [safeNonce]: the transactions
     * queued before it run first, and only the next one can be executed.
     */
    data class NotNext(val safeNonce: Long) : SafeTxRefusal

    /** [address] is not one of the Safe's current owners, as its verified storage says. */
    data class NotAnOwner(val address: Address) : SafeTxRefusal

    /** The signature said to be [owner]'s does not recover to [owner] over the hash computed here. */
    data class BadSignature(val owner: Address) : SafeTxRefusal

    /**
     * A delegate call: the Safe would run the target's code as itself, and the code could do
     * anything to it, so this wallet neither signs, approves nor executes one (docs/wallet-ux.md,
     * screen 17). It is shown, decoded as such, and nothing more.
     */
    data object DelegateCall : SafeTxRefusal

    /** [owner] has already signed it: there is nothing to add. */
    data class AlreadySigned(val owner: Address) : SafeTxRefusal

    /** The owner has already approved its hash on chain. */
    data object AlreadyApproved : SafeTxRefusal

    /**
     * This account's approval or execution of it was sent already and no block has it yet
     * ([eth.serotine.domain.repository.SentTransfers]): a second one would only pay a fee to
     * revert, or to approve twice.
     */
    data object AlreadySent : SafeTxRefusal

    /** [confirmations] of the Safe's owners confirm it, fewer than its [threshold]. */
    data class ThresholdNotMet(val confirmations: Int, val threshold: Int) : SafeTxRefusal

    /**
     * It carries gas or refund terms ([eth.serotine.domain.model.SafeTxGasTerms]): a refund can
     * pay the Safe's funds to whoever it names, bounded only by what the Safe holds, and a
     * `safeTxGas` lets the call fail and still use up the nonce. This wallet proposes none, and
     * signs, approves and executes none either (docs/wallet-ux.md, screen 17); it is shown,
     * with its terms, and nothing more.
     */
    data object GasTerms : SafeTxRefusal

    /** The light client has no verified head yet. */
    data object Syncing : SafeTxRefusal

    /** A verified figure could not be had right now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : SafeTxRefusal

    /** The transaction the owner account would send was not quoted: [refusal]. */
    data class NotQuoted(val refusal: CallRefusal) : SafeTxRefusal
}

/**
 * A Safe transaction as this wallet shows it: [request], what it does as decoded here from its
 * raw fields ([action]), and the gas and refund terms it carries beside its call ([gasTerms],
 * null: none). A [delegateCall] is marked as such. [refusal] says why this wallet will not sign,
 * approve or execute it, before anyone asks: a delegate call, or gas terms (null: neither).
 * Made only by [of], so what is shown is always what the request's fields decode to.
 */
@ConsistentCopyVisibility
data class DecodedSafeTx private constructor(
    val request: SigningRequest,
    val action: SafeTxAction,
    val gasTerms: SafeTxGasTerms?,
) {

    /** Whether the Safe would run the target's code as itself. */
    val delegateCall: Boolean
        get() = request.tx.operation == SafeOperation.DELEGATECALL

    /** Why this wallet signs, approves and executes none of it, or null when it may. */
    val refusal: SafeTxRefusal?
        get() = request.unsignable()

    companion object {
        /** [request], decoded. */
        fun of(request: SigningRequest) =
            DecodedSafeTx(request, SafeTxAction.of(request.safe, request.tx), SafeTxGasTerms.of(request.tx))
    }
}

/**
 * Why this wallet signs, approves and executes none of [this], whatever its signatures and the
 * Safe say: a delegate call, or gas or refund terms (docs/wallet-ux.md, screen 17). Null when it
 * is a plain call, the only kind it proposes.
 */
internal fun SigningRequest.unsignable(): SafeTxRefusal? = when {
    tx.operation == SafeOperation.DELEGATECALL -> SafeTxRefusal.DelegateCall
    SafeTxGasTerms.of(tx) != null -> SafeTxRefusal.GasTerms
    else -> null
}

/** The Safe's verified state for a one-shot check, or the refusal there is instead. */
internal sealed interface SafeCheck {
    data class Verified(val reading: SafeReading.Verified) : SafeCheck
    data class Refused(val refusal: SafeTxRefusal) : SafeCheck
}

/** The Safe at [safe] as the light client proves it now. */
internal suspend fun ObserveSafe.check(safe: Address): SafeCheck = when (val reading = this(safe).first()) {
    is SafeReading.Verified -> SafeCheck.Verified(reading)
    SafeReading.Checking -> SafeCheck.Refused(SafeTxRefusal.Syncing)
    SafeReading.NotASafe -> SafeCheck.Refused(SafeTxRefusal.NotASafe)
    is SafeReading.Unavailable -> SafeCheck.Refused(SafeTxRefusal.Unavailable(reading.reason))
}

/**
 * [request]'s signatures that are current owners' (of [owners]) and recover to them over the
 * hash computed here; or the first that is not, as a refusal ([SafeTxRefusal.NotAnOwner],
 * [SafeTxRefusal.BadSignature]).
 */
internal fun SignatureRecovery.check(request: SigningRequest, owners: List<Address>): SafeTxRefusal? {
    for (signature in request.signatures) {
        if (signature.owner !in owners) return SafeTxRefusal.NotAnOwner(signature.owner)
        if (recover(request.hash, signature.signature) != signature.owner) {
            return SafeTxRefusal.BadSignature(signature.owner)
        }
    }
    return null
}

/** [request]'s signatures that are [owners]' and recover to them: those a Safe accepts now. */
internal fun SignatureRecovery.valid(request: SigningRequest, owners: List<Address>): List<SafeSignature.Ecdsa> =
    request.signatures.filter { it.owner in owners && recover(request.hash, it.signature) == it.owner }

/**
 * [request] with only the signatures [valid] keeps: what is shown and shared of it, so that a
 * signature of an owner removed since it was queued never makes another device refuse it.
 */
internal fun SignatureRecovery.validOnly(request: SigningRequest, owners: List<Address>): SigningRequest =
    SigningRequest.of(request.network, request.safe, request.tx, valid(request, owners))

/**
 * Imports a signing request that came from another device as text (CLAUDE.md, *Data sources*:
 * no off-chain service), and checks it against the chain before anything of it is believed:
 * the text decodes strictly ([SigningRequestCodec]) for the network the app works on; the Safe
 * is one this app recognises, as its verified code and storage say ([ObserveSafe]); the
 * request's nonce is not below the Safe's verified nonce (equal: it is next; above: it is
 * queued behind the ones before it); its hash is computed here from the raw fields; and every
 * signature it carries is a current owner's and recovers to that owner over that hash
 * ([SignatureRecovery]). Anything else is refused with the reason, and nothing of it is kept.
 *
 * What it does is decoded here from the fields ([DecodedSafeTx]); a delegate call or gas terms
 * are accepted for display, marked as such, and never signed here. A request that passes is
 * saved to the queue ([SafeTxQueue]), its signatures merged into the same transaction's there.
 * The text, up to the codec's limit, is decoded on [decoding], never on the caller's thread.
 */
class ImportSigningRequest(
    private val codec: SigningRequestCodec,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val queue: SafeTxQueue,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend operator fun invoke(text: String): SigningRequestImport {
        val request = when (val decoded = withContext(decoding) { codec.decode(text) }) {
            is SigningRequestDecoding.Decoded -> decoded.request
            SigningRequestDecoding.Malformed -> return SigningRequestImport.Refused(SafeTxRefusal.Malformed)
            is SigningRequestDecoding.UnknownVersion ->
                return SigningRequestImport.Refused(SafeTxRefusal.UnknownVersion(decoded.version))
            is SigningRequestDecoding.WrongChain ->
                return SigningRequestImport.Refused(SafeTxRefusal.WrongChain(decoded.chainId))
        }
        val safe = when (val check = observeSafe.check(request.safe)) {
            is SafeCheck.Verified -> check.reading
            is SafeCheck.Refused -> return SigningRequestImport.Refused(check.refusal)
        }
        val state = safe.state
        if (request.nonce < state.nonce) return SigningRequestImport.Refused(SafeTxRefusal.NonceUsed(state.nonce))
        recovery.check(request, state.owners)?.let { return SigningRequestImport.Refused(it) }
        queue.prune(request.safe, state.nonce)
        val saved = queue.save(request)
        // Shown and shared with what the Safe accepts now of what was queued here before.
        val queued = recovery.validOnly(saved ?: request, state.owners)
        return SigningRequestImport.Imported(DecodedSafeTx.of(queued), safe, saved = saved != null)
    }
}

/** The outcome of [ImportSigningRequest]. */
sealed interface SigningRequestImport {

    /**
     * [tx] passed every check against [safe], the Safe's verified state (its owners, threshold
     * and nonce, at its block), and is queued with the signatures it carried and those it had
     * here already; [saved] false when the queue could not be saved, so it is shown but not kept.
     */
    data class Imported(val tx: DecodedSafeTx, val safe: SafeReading.Verified, val saved: Boolean) :
        SigningRequestImport {
        /** How many of the Safe's transactions must run before this one: 0 when it is next. */
        val queuedBehind: Long
            get() = tx.request.nonce - safe.state.nonce
    }

    /** Not imported: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SigningRequestImport
}

/**
 * A signing request as the text it travels in to another device ([SigningRequestCodec]): the
 * raw fields and the signatures collected so far, never a description of them — Share request
 * on docs/wallet-ux.md's screen 17.
 */
class EncodeSigningRequest(private val codec: SigningRequestCodec) {
    operator fun invoke(request: SigningRequest): String = codec.encode(request)
}

/**
 * Signs a Safe transaction with an owner key held here: [Signer] signs its hash — the EIP-712
 * digest computed here from the raw fields, as given, with no `eth_sign` prefix — with
 * [owner]'s key as [unlock] opens it. Only a current owner of the Safe, as its verified storage
 * says, signs, and only a transaction whose nonce the Safe has not passed; a delegate call is
 * refused (docs/wallet-ux.md, screen 17). The signature is checked to recover to [owner] before
 * it is kept, and saved to the queue with the request's other signatures that are current
 * owners' and recover to them ([SafeTxQueue]). On [network] only.
 */
class SignSafeTx(
    private val signer: Signer,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val queue: SafeTxQueue,
    private val network: Network,
) {
    suspend operator fun invoke(request: SigningRequest, owner: Address, unlock: Unlock): SafeTxSigning {
        fun refused(refusal: SafeTxRefusal) = SafeTxSigning.Refused(refusal)
        if (request.network != network) return refused(SafeTxRefusal.WrongChain(request.network.chainId))
        request.unsignable()?.let { return refused(it) }
        val state = when (val check = observeSafe.check(request.safe)) {
            is SafeCheck.Verified -> check.reading.state
            is SafeCheck.Refused -> return refused(check.refusal)
        }
        if (request.nonce < state.nonce) return refused(SafeTxRefusal.NonceUsed(state.nonce))
        if (owner !in state.owners) return refused(SafeTxRefusal.NotAnOwner(owner))
        // Of the signatures the request carries, only the checked ones count: a forged one in the
        // owner's name neither stands for it nor keeps it from signing.
        val valid = recovery.valid(request, state.owners)
        if (valid.any { it.owner == owner }) return refused(SafeTxRefusal.AlreadySigned(owner))
        val signature = when (val signing = signer.sign(owner, request.hash, unlock)) {
            is Signing.Signed -> signing.signature
            else -> return SafeTxSigning.NotSigned(signing)
        }
        // The key store's signature is checked like a co-signer's: one that recovers to another
        // address is never kept, nor shared.
        if (recovery.recover(request.hash, signature) != owner) return refused(SafeTxRefusal.BadSignature(owner))
        // What is queued is checked: the carried signatures that count, with this one.
        val signed = SigningRequest.of(request.network, request.safe, request.tx, valid)
            .withSignatures(listOf(SafeSignature.Ecdsa(owner, signature)))
        queue.prune(request.safe, state.nonce)
        val saved = queue.save(signed)
        // Shown and shared with what the Safe accepts now of what was queued here before.
        return SafeTxSigning.Signed(recovery.validOnly(saved ?: signed, state.owners), saved = saved != null)
    }
}

/** The outcome of [SignSafeTx]. */
sealed interface SafeTxSigning {

    /**
     * Signed: [request] carries the signature now, with those queued already; [saved] false when
     * the queue could not be saved (the request can still be shared, but is not kept).
     */
    data class Signed(val request: SigningRequest, val saved: Boolean) : SafeTxSigning

    /** Not signed, for the reason [signing] gives: nothing left the key store. */
    data class NotSigned(val signing: Signing) : SafeTxSigning

    /** Not signed: [refusal]. */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxSigning
}

/**
 * Proposes a transfer from a Safe (docs/wallet-ux.md, screen 17): [amount] of [asset] to a
 * recipient, as a plain call with no refund ([SafeTx.call]) at the Safe's next nonce — its
 * verified nonce, or the first after the transactions queued for it from that nonce on without a
 * gap ([SafeTxQueue]): one queued further ahead, which cannot run before the gap is filled, moves
 * nothing, so a request imported at an absurd nonce cannot push every proposal behind it. Nothing
 * is proposed on less than verified state: the Safe's state ([ObserveSafe]) and its balance of
 * the asset, of which what the transactions queued before it may move (decoded from their raw
 * fields) is not this one's to move.
 *
 * Simulated where the engine allows: the Safe's call itself is run as a verified call from the
 * Safe's address at the head ([TransactionInputs.simulate]), and a revert, or a token's
 * `transfer` that returns false, stops it here. What is not simulated: the Safe's own checks
 * (its owners' signatures, which do not exist yet, and its nonce) and its guard, if it has one;
 * those run when its execution is quoted ([QuoteSafeExecution]), as `execTransaction` itself.
 * An engine that will not run a call from a contract's address leaves it not simulated, and the
 * proposal says so. No state override is used.
 *
 * Nothing is saved: signing it ([SignSafeTx]) queues it. On [network] only.
 */
class ProposeSafeTx(
    private val observeSafe: ObserveSafe,
    private val nativeBalances: NativeBalances,
    private val tokenBalances: TokenBalances,
    private val inputs: TransactionInputs,
    private val queue: SafeTxQueue,
    private val network: Network,
) {
    suspend operator fun invoke(safe: Address, to: Address, asset: TransferAsset, amount: UInt256): SafeTxProposing =
        coroutineScope {
            val token = (asset as? TransferAsset.Erc20Token)?.token
            if (to == Address.ZERO) return@coroutineScope SafeTxProposing.ToZeroAddress
            if (token != null && to == token.address) return@coroutineScope SafeTxProposing.ToTokenContract
            if (amount.isZero) return@coroutineScope SafeTxProposing.NothingToSend
            val txTo = token?.address ?: to
            val value = if (token == null) amount else UInt256.ZERO
            val data = if (token == null) ByteArray(0) else Erc20.transferData(to, amount)
            val checked = async { observeSafe.check(safe) }
            val balance = async { balanceOf(safe, token?.address) }
            val queued = async { queue.observe(safe).first() }

            val reading = when (val check = checked.await()) {
                is SafeCheck.Verified -> check.reading
                is SafeCheck.Refused -> return@coroutineScope SafeTxProposing.Refused(check.refusal)
            }
            val (before, balanceBlock) = when (val read = balance.await()) {
                is SafeBalance.Read -> read.amount to read.blockNumber
                is SafeBalance.Refused -> return@coroutineScope read.proposing
            }
            if (before < amount) return@coroutineScope insufficient(token != null)
            val safeNonce = reading.state.nonce
            // A queue that cannot be read leaves the Safe's nonce: two transactions at one nonce
            // are two proposals, only one of which can ever run.
            val pending = (queued.await() as? SafeTxQueueList.Loaded)?.requests.orEmpty()
                .filter { it.network == network && it.nonce >= safeNonce }
            val taken = pending.mapTo(HashSet()) { it.nonce }
            var nonce = safeNonce
            while (nonce in taken) nonce++
            val ahead = pending.filter { it.nonce < nonce }
            // What the ones before it move of the asset is not this one's to move: per nonce the most
            // any of its alternatives moves, since only one transaction at a nonce ever runs.
            val queuedAmount = ahead.groupBy { it.nonce }.values
                .map { alternatives -> alternatives.maxOf { it.moves(token?.address) } }
                .sumOrNull()
                ?: return@coroutineScope insufficient(token != null)
            val available = before.minusOrNull(queuedAmount)?.takeIf { it >= amount }
                ?: return@coroutineScope insufficient(token != null)
            val simulation = when (val run = inputs.simulate(safe, txTo, value, data)) {
                is Simulation.Succeeded -> {
                    val refusedByToken = token != null && run.returnHex.isNotEmpty() && run.returnHex.all { it == '0' }
                    if (refusedByToken) return@coroutineScope SafeTxProposing.Reverts(run.blockNumber)
                    SafeCallSimulation.Ran(run.blockNumber)
                }
                is Simulation.Reverted -> return@coroutineScope SafeTxProposing.Reverts(run.blockNumber)
                Simulation.Refused -> SafeCallSimulation.NotRun
                is Simulation.Unavailable ->
                    return@coroutineScope SafeTxProposing.Refused(SafeTxRefusal.Unavailable(run.reason))
            }
            queue.prune(safe, safeNonce)
            val request = SigningRequest.of(network, safe, SafeTx.call(txTo, value, data, nonce))
            SafeTxProposing.Proposed(
                SafeTxProposal(
                    tx = DecodedSafeTx.of(request),
                    safe = reading,
                    queuedAhead = (nonce - safeNonce).toInt(),
                    balanceBefore = before,
                    queuedAmount = queuedAmount,
                    balanceAfter = available - amount,
                    balanceBlock = balanceBlock,
                    simulation = simulation,
                ),
            )
        }

    private fun insufficient(token: Boolean) =
        if (token) SafeTxProposing.InsufficientToken else SafeTxProposing.InsufficientFunds

    /** The Safe's verified balance of the token at [token], or of the network's currency (null). */
    private suspend fun balanceOf(safe: Address, token: Address?): SafeBalance {
        fun refused(refusal: SafeTxRefusal) = SafeBalance.Refused(SafeTxProposing.Refused(refusal))
        val unproven = refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN))
        if (token == null) {
            return when (val reading = nativeBalances.observeBalance(safe).first()) {
                is BalanceReading.Verified ->
                    reading.wei.toUInt256OrNull()?.let { SafeBalance.Read(it, reading.blockNumber) } ?: unproven
                BalanceReading.Syncing -> refused(SafeTxRefusal.Syncing)
                is BalanceReading.Unavailable -> refused(SafeTxRefusal.Unavailable(reading.reason))
            }
        }
        return when (val reading = tokenBalances.observeBalance(safe, token).first()) {
            is TokenBalanceReading.Verified ->
                UInt256.parse(reading.units.decimal)?.let { SafeBalance.Read(it, reading.blockNumber) } ?: unproven
            TokenBalanceReading.Syncing -> refused(SafeTxRefusal.Syncing)
            TokenBalanceReading.NotAToken -> SafeBalance.Refused(SafeTxProposing.NotAToken)
            is TokenBalanceReading.Unavailable -> refused(SafeTxRefusal.Unavailable(reading.reason))
        }
    }

    private sealed interface SafeBalance {
        data class Read(val amount: UInt256, val blockNumber: Long?) : SafeBalance
        data class Refused(val proposing: SafeTxProposing) : SafeBalance
    }

    /**
     * What this queued request may move of the token at [token] (null: of the network's currency),
     * as decoded from its fields: a token's transfers of it, or for the currency the value of
     * whatever it calls. A delegate call's value is not sent, and what its code may do is not
     * known here.
     */
    private fun SigningRequest.moves(token: Address?): UInt256 = when (val action = SafeTxAction.of(safe, tx)) {
        is SafeTxAction.TokenTransfer -> if (action.token == token) action.amount else UInt256.ZERO
        is SafeTxAction.DelegateCall -> UInt256.ZERO
        else -> if (token == null) tx.value else UInt256.ZERO
    }
}

/**
 * A Safe transfer proposed for review: [tx] at the Safe's next nonce, after [queuedAhead]
 * nonces queued before it (two transactions at one nonce, of which one runs, count once); the Safe's verified state ([safe]); its balance of the asset
 * ([balanceBefore], at [balanceBlock]), what those queued before it may move of it
 * ([queuedAmount]: per nonce the most of its alternatives, as only one of them runs) and what
 * this one leaves ([balanceAfter]); and whether the Safe's call was
 * run as a verified call ([simulation]). Only [ProposeSafeTx] makes one.
 */
@ConsistentCopyVisibility
data class SafeTxProposal internal constructor(
    val tx: DecodedSafeTx,
    val safe: SafeReading.Verified,
    val queuedAhead: Int,
    val balanceBefore: UInt256,
    val queuedAmount: UInt256,
    val balanceAfter: UInt256,
    val balanceBlock: Long?,
    val simulation: SafeCallSimulation,
) {
    /** The request its owners sign. */
    val request: SigningRequest
        get() = tx.request
}

/** Whether a Safe's call was simulated as a verified call from the Safe. */
sealed interface SafeCallSimulation {

    /** It ran without reverting against the state of [blockNumber] (null: not said). */
    data class Ran(val blockNumber: Long?) : SafeCallSimulation

    /** The engine will not run a call from the Safe's address on this build: not simulated. */
    data object NotRun : SafeCallSimulation
}

/** The outcome of [ProposeSafeTx]. */
sealed interface SafeTxProposing {

    /** [proposal] is ready for review. */
    data class Proposed(val proposal: SafeTxProposal) : SafeTxProposing

    /** The recipient is the zero address: nobody holds its key, so the amount would be lost for good. */
    data object ToZeroAddress : SafeTxProposing

    /** A token sent to the token's own contract, from which nobody can move it out: refused. */
    data object ToTokenContract : SafeTxProposing

    /** The amount is zero: there is nothing to send. */
    data object NothingToSend : SafeTxProposing

    /** The Safe's balance of the network's currency, less what those queued before it may move, is below the amount. */
    data object InsufficientFunds : SafeTxProposing

    /** The Safe holds less of the token than the amount, less what those queued before it may move. */
    data object InsufficientToken : SafeTxProposing

    /** The token's address answers as no token. */
    data object NotAToken : SafeTxProposing

    /** The Safe's call reverts (or the token's `transfer` returns false), run from the Safe at [blockNumber]. */
    data class Reverts(val blockNumber: Long?) : SafeTxProposing

    /** Not proposed: the Safe is none, or its state or balance cannot be verified now ([refusal]). */
    data class Refused(val refusal: SafeTxRefusal) : SafeTxProposing
}

/**
 * The transactions queued for the Safe at [safe] (docs/wallet-ux.md, screen 16, Queue), each
 * decoded here from its raw fields, in nonce order. Once the Safe's state is verified, those whose
 * nonce it has passed are left out, so are the signatures that are not its current owners' or
 * do not recover to them ([SignatureRecovery]: what is shared from here is then what another
 * device accepts, whatever the queue file holds), and [SafeTxQueueReading.Loaded.safeNonce] says
 * its nonce. On [network] only.
 */
class ObserveSafeTxQueue(
    private val queue: SafeTxQueue,
    private val observeSafe: ObserveSafe,
    private val recovery: SignatureRecovery,
    private val network: Network,
) {
    operator fun invoke(safe: Address): Flow<SafeTxQueueReading> =
        combine(queue.observe(safe), observeSafe(safe)) { list, reading ->
            when (list) {
                SafeTxQueueList.Unreadable -> SafeTxQueueReading.Unreadable
                is SafeTxQueueList.Loaded -> {
                    val state = (reading as? SafeReading.Verified)?.state
                    val pending = list.requests
                        .filter { it.network == network && (state == null || it.nonce >= state.nonce) }
                        .sortedBy { it.nonce }
                        // Checked again: an owner removed since, or a file changed under it, counts no more.
                        .map { request -> state?.let { recovery.validOnly(request, it.owners) } ?: request }
                    SafeTxQueueReading.Loaded(pending.map(DecodedSafeTx::of), state?.nonce)
                }
            }
        }
}

/** One answer of [ObserveSafeTxQueue]. */
sealed interface SafeTxQueueReading {

    /** The [transactions] queued, in nonce order; [safeNonce] the Safe's verified nonce (null: not verified now). */
    data class Loaded(val transactions: List<DecodedSafeTx>, val safeNonce: Long?) : SafeTxQueueReading

    /** The queue cannot be read right now. */
    data object Unreadable : SafeTxQueueReading
}

/**
 * Takes the transaction whose hash is [hash] out of this device's queue: a proposal given up.
 * Only here: a signature already shared stays valid elsewhere until the Safe's nonce passes it.
 */
class RemoveQueuedSafeTx(private val queue: SafeTxQueue) {
    suspend operator fun invoke(hash: Digest): Boolean = queue.remove(hash)
}
