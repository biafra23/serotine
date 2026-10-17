package eth.serotine.ui.safetx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.ApproveSafeTxOnChain
import eth.serotine.domain.usecase.CallQuote
import eth.serotine.domain.usecase.DecodedSafeTx
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.EncodeSigningRequest
import eth.serotine.domain.usecase.ExecuteSafeTx
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.OwnerConfirmation
import eth.serotine.domain.usecase.QuoteSafeApproval
import eth.serotine.domain.usecase.QuoteSafeExecution
import eth.serotine.domain.usecase.ReadSafeTxOwners
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RemoveQueuedSafeTx
import eth.serotine.domain.usecase.SafeApprovalQuote
import eth.serotine.domain.usecase.SafeApprovalQuoting
import eth.serotine.domain.usecase.SafeExecutionQuote
import eth.serotine.domain.usecase.SafeExecutionQuoting
import eth.serotine.domain.usecase.SafeTxOwnersReading
import eth.serotine.domain.usecase.SafeTxQueueReading
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.SafeTxSigning
import eth.serotine.domain.usecase.SignSafeTx
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.QrMatrix
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.forDecoding
import eth.serotine.ui.components.gasTermsItem
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.send.BalanceChange
import eth.serotine.ui.send.QUOTE_MAX_AGE
import eth.serotine.ui.send.STALE_QUOTE
import eth.serotine.ui.send.exactAmount
import eth.serotine.ui.send.feeDetail
import eth.serotine.ui.send.verifiedAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Safe transaction screen's ViewModel (docs/wallet-ux.md, screen 17): the transaction [hash]
 * of the Safe at [safe], as this device's queue holds it ([ObserveSafeTxQueue]: decoded here from
 * its raw fields, with only the signatures the Safe accepts now), where each current owner stands
 * on it ([ReadSafeTxOwners], read again whenever its signatures change, on Check again and when
 * the screen is shown again), on [network]. Its intents:
 * - Share request: the request as the text another device imports ([EncodeSigningRequest]), in
 *   one QR code where it fits.
 * - Sign: an owner held here that has not confirmed it signs its hash ([SignSafeTx]); the signed
 *   request is queued. One that could not be saved stays on this screen, to be shared before it
 *   is left.
 * - Approve on chain ([QuoteSafeApproval] → review → [ApproveSafeTxOnChain]) and Execute, once
 *   the confirmations reach the threshold ([QuoteSafeExecution] → review → [ExecuteSafeTx]), from
 *   an account the wallet holds, a held owner first. Their reviews follow Send's (screen 8): a
 *   quote made when the review is entered, again on Refresh, when the screen is shown again after
 *   it was not, and at the tap that would sign it when it is older than [QUOTE_MAX_AGE]
 *   ([timeSource] tells); one signed but not broadcast is retried with its own bytes
 *   ([RebroadcastTransfer]), never quoted or signed anew.
 * - Remove from this device ([RemoveQueuedSafeTx]).
 *
 * Nothing it signs is ever anything but the transaction decoded here. One of the ViewModels the
 * [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in the signing step's state
 * while the field is shown, hands it to the signer in an [Unlock], and never logs, saves or caches
 * it; it is dropped once used, when the screen is not shown ([onHidden]) and when it is gone.
 */
class SafeTxViewModel(
    private val safe: Address,
    private val hash: Digest,
    observeAccounts: ObserveAccounts,
    observeSafeTxQueue: ObserveSafeTxQueue,
    observeKnownTokens: ObserveKnownTokens,
    private val readSafeTxOwners: ReadSafeTxOwners,
    private val encodeSigningRequest: EncodeSigningRequest,
    private val describeKeyStore: DescribeKeyStore,
    private val signSafeTx: SignSafeTx,
    private val quoteSafeApproval: QuoteSafeApproval,
    private val approveSafeTxOnChain: ApproveSafeTxOnChain,
    private val quoteSafeExecution: QuoteSafeExecution,
    private val executeSafeTx: ExecuteSafeTx,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val removeQueuedSafeTx: RemoveQueuedSafeTx,
    private val network: Network,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    /** What the screen is drawn from: a transaction to act on ([Context]), or why there is none ([Queued]). */
    private sealed interface Source

    /** The transaction as the queue holds it, or why there is none. */
    private sealed interface Queued : Source {
        data object Loading : Queued
        data object Unreadable : Queued

        /** [tx], with the Safe's verified nonce ([safeNonce], null: not verified now). */
        data class Found(val tx: DecodedSafeTx, val safeNonce: Long?) : Queued

        /**
         * Not in the queue; [lastNonce] the nonce it had when it last was (null: never seen here), and
         * [passed] when the Safe's nonce has passed it since.
         */
        data class Missing(val lastNonce: Long?, val passed: Boolean) : Queued
    }

    /**
     * Everything the screen and its intents work from: the transaction [found] in the queue, the
     * [request] shown and shared, where its owners stand ([owners], null while read), what can be
     * done with it ([options]), the [tokens] a transfer is shown in, and the wallet's accounts by
     * address ([labels]).
     */
    private class Context(
        val found: Queued.Found,
        val request: SigningRequest,
        val owners: SafeTxOwnersReading?,
        val options: Options,
        val tokens: List<Token>,
        val labels: Map<Address, String>,
    ) : Source {
        fun labelOf(address: Address): String = labels[address] ?: shortAddress(Eip55.format(address))
    }

    /** Who can do what with the transaction now. */
    private class Options(
        /** Held owners that have not confirmed it: who may sign it. */
        val signers: List<Address>,
        /** Held owners that have not confirmed it, while the confirmations fall short: who may approve it. */
        val approvers: List<Address>,
        /** Accounts held here that can execute it now, held owners first. */
        val executors: List<Address>,
        /** Why it cannot be executed now, or null. */
        val executeNote: String?,
    )

    /** An approval or an execution under review. */
    private sealed interface TxQuote {
        val call: CallQuote

        data class Approval(val quote: SafeApprovalQuote) : TxQuote {
            override val call: CallQuote
                get() = quote.call
        }

        data class Execution(val quote: SafeExecutionQuote) : TxQuote {
            override val call: CallQuote
                get() = quote.call
        }
    }

    /**
     * A transaction signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        /** Whether sending the same bytes again may change the verdict. */
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable
    }

    /** Where the screen stands. */
    private sealed interface Step {
        data object Overview : Step

        /** An approval or execution from [account] at [level] being quoted for review. */
        data class Quoting(val kind: ReviewKind, val account: Address, val level: FeeLevel) : Step

        /** Signing the hash as [owner]. */
        data class Signing(val owner: Address, val unlock: UnlockStep, val signing: Boolean = false) : Step

        /** [quote], made at [quotedAt], under review; [hidden] once the screen stopped being shown since. */
        data class Reviewing(
            val kind: ReviewKind,
            val quote: TxQuote,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
        ) : Step {
            val account: Address
                get() = quote.call.from

            val level: FeeLevel
                get() = quote.call.level

            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

        data class Sent(val hash: String) : Step
        data object Removed : Step
    }

    /** What the overview shows beside the transaction: the share sheet opened, and what the last action came to. */
    private data class View(
        val sharing: Boolean = false,
        val message: String? = null,
        /** The request signed here whose signature the queue could not save: shown and shared until left. */
        val unsaved: SigningRequest? = null,
    )

    private val view = MutableStateFlow(View())
    private val step = MutableStateFlow<Step>(Step.Overview)

    /** Asks for the owners to be read again. */
    private val readAgain = MutableStateFlow(0)

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden since it was last shown: shown again, the owners are read again. */
    private var hiddenSince = false

    /** The transaction as the queue holds it, each reading judged against the one before. */
    private val queued: Flow<Queued> = observeSafeTxQueue(safe)
        .scan<SafeTxQueueReading, Queued>(Queued.Loading, ::queuedOf)
        .shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            replay = 1,
        )

    /** The request shown and shared: the queue's, with a signature made here that it could not save. */
    private val request: Flow<SigningRequest?> = combine(queued, view) { queued, view ->
        val request = (queued as? Queued.Found)?.tx?.request ?: return@combine null
        view.unsaved?.takeIf { it.hash == request.hash }?.let { request.withSignatures(it.signatures) } ?: request
    }.distinctUntilChanged()

    /** Where the owners stand on [request], read again when it changes and when asked; null while read first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val owners: Flow<SafeTxOwnersReading?> = combine(request, readAgain) { request, _ -> request }
        .mapLatest { request -> request?.let { readOwners(it) } }
        .onStart { emit(null) }

    private val accounts: Flow<List<Account>> = observeAccounts()
        .map { (it as? AccountList.Loaded)?.accounts.orEmpty() }

    /** What the screen shows and acts on, or why there is no transaction to act on ([Queued]). */
    private val context: StateFlow<Source?> =
        combine(queued, request, owners, accounts, observeKnownTokens()) { queued, request, owners, accounts, tokens ->
            if (queued !is Queued.Found || request == null) return@combine queued
            val held = accounts.filter { it.kind == AccountKind.EOA && it.custody == AccountCustody.InKeyStore }
            Context(
                found = queued,
                request = request,
                owners = owners,
                options = options(queued.tx, owners as? SafeTxOwnersReading.Read, held),
                tokens = tokens.forDecoding(),
                labels = accounts.associate { it.address to it.label },
            )
        }.stateIn(
            viewModelScope,
            // Back after the timeout, no old reading stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SafeTxUiState> = combine(context, view, step, ::render).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SafeTxUiState.Loading(network.displayName),
    )

    /** The transaction to act on now, or null while there is none. */
    private val now: Context?
        get() = context.value as? Context

    /** Opens the request for sharing: its text, in one QR code where it fits. */
    fun onShare() = view.update { it.copy(sharing = true) }

    /** Closes the request's sharing. */
    fun onShareClosed() = view.update { it.copy(sharing = false) }

    /** Reads where the owners stand again: an approval mined since, a signature imported elsewhere. */
    fun checkAgain() = readAgain.update { it + 1 }

    /** Starts signing the hash with the first owner held here that has not confirmed it. */
    fun startSigning() {
        if (step.value != Step.Overview) return
        val owner = now?.options?.signers?.firstOrNull() ?: return
        view.update { it.copy(message = null, sharing = false) }
        step.value = Step.Signing(owner, UnlockStep())
        viewModelScope.launch {
            val biometrics = describeKeyStore.biometricsOn()
            step.update { current ->
                if (current is Step.Signing && !current.signing) {
                    current.copy(unlock = current.unlock.copy(biometrics = biometrics))
                } else {
                    current
                }
            }
        }
    }

    /** Signs as the owner at [address] (EIP-55) instead. */
    fun onSignerChange(address: String) {
        val owner = Address.parse(address) ?: return
        step.update { current ->
            if (current is Step.Signing && !current.signing && owner in now?.options?.signers.orEmpty()) {
                current.copy(owner = owner, unlock = current.unlock.copy(error = null))
            } else {
                current
            }
        }
    }

    /** Quotes the approval on chain from the first owner held here that has not confirmed it, for review. */
    fun startApproval() {
        val owner = now?.options?.approvers?.firstOrNull() ?: return
        quoteForReview(ReviewKind.APPROVAL, owner, FeeLevel.NORMAL)
    }

    /** Quotes the execution from the first account held here that can execute it, a held owner first, for review. */
    fun startExecution() {
        val executor = now?.options?.executors?.firstOrNull() ?: return
        quoteForReview(ReviewKind.EXECUTION, executor, FeeLevel.NORMAL)
    }

    /** Sends the reviewed approval or execution from the account at [address] (EIP-55) instead: quoted anew. */
    fun onAccountChange(address: String) {
        val account = Address.parse(address) ?: return
        val review = step.value as? Step.Reviewing ?: return
        if (!review.idle || account == review.account) return
        val options = now?.options ?: return
        val allowed = if (review.kind == ReviewKind.APPROVAL) options.approvers else options.executors
        if (account !in allowed) return
        requote(review, account, review.level, note = null)
    }

    /** Sends the reviewed approval or execution at the fee [level] instead: quoted anew. */
    fun onFeeChange(level: FeeLevel) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || level == current.level) return
        requote(current, current.account, level, note = null)
    }

    /** Quotes the reviewed approval or execution again: fresh fees, nonce and balance. */
    fun refresh() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        requote(current, current.account, current.level, note = null)
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        when {
            current is Step.Signing && !current.signing ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            current is Step.Reviewing && !current.signing && current.notSent == null ->
                current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
            else -> current
        }
    }

    /** Signs with the typed wallet password: the hash in the signing step, the transaction in a review. */
    fun sign() {
        when (val current = step.value) {
            is Step.Signing -> {
                val password = current.unlock.password
                if (current.signing || password.text.isEmpty()) return
                signHash(current, Unlock.WithPassword(password))
            }
            is Step.Reviewing -> {
                val password = current.unlock.password
                if (!current.idle || password.text.isEmpty() || requoted(current)) return
                send(current, Unlock.WithPassword(password))
            }
            else -> Unit
        }
    }

    /** Signs with a fingerprint or face: the hash in the signing step, the transaction in a review. */
    fun signWithBiometrics() {
        val action = now?.let(::promptText) ?: return
        when (val current = step.value) {
            is Step.Signing -> {
                if (current.signing || !current.unlock.biometrics) return
                signHash(current, Unlock.WithBiometrics("Sign Safe transaction $action"))
            }
            is Step.Reviewing -> {
                if (!current.idle || !current.unlock.biometrics || requoted(current)) return
                val what = if (current.kind == ReviewKind.APPROVAL) "Approve on chain" else "Execute"
                send(current, Unlock.WithBiometrics("$what Safe transaction $action"))
            }
            else -> Unit
        }
    }

    /** Broadcasts the approval or execution signed but not sent again, byte for byte. */
    fun retry() {
        val current = step.value as? Step.Reviewing ?: return
        val notSent = current.notSent ?: return
        if (current.signing || !notSent.canRetry) return
        step.value = current.copy(signing = true)
        job = viewModelScope.launch {
            val sending = safely { rebroadcastTransfer(notSent.transfer) }
            step.update { latest ->
                when {
                    latest !is Step.Reviewing -> latest
                    sending is TransferSending.Sent -> Step.Sent(sending.hash)
                    sending is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    sending is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    else -> latest.copy(signing = false)
                }
            }
        }
    }

    /**
     * Back from the signing step or a review to the overview: the password and the quote go. Not
     * while signing, nor after a broadcast no peer answered: those bytes may be out there, and only
     * a retry of them may send them again.
     */
    fun back() {
        when (val current = step.value) {
            is Step.Signing -> if (current.signing) return
            is Step.Reviewing -> if (current.signing || current.notSent?.canRetry == true) return
            is Step.Quoting -> Unit
            else -> return
        }
        job?.cancel()
        step.value = Step.Overview
    }

    /** Takes the transaction out of this device's queue; the screen then leaves. */
    fun remove() {
        if (step.value != Step.Overview) return
        viewModelScope.launch {
            val removed = try {
                removeQueuedSafeTx(hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false // the port promises not to throw; the backstop
            }
            if (removed) {
                step.value = Step.Removed
            } else {
                view.update { it.copy(message = "This device's queue cannot be saved right now: nothing was removed.") }
            }
        }
    }

    /**
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and a review
     * is quoted again when it is shown again ([onShown]). A signature already running finishes.
     */
    fun onHidden() {
        shown = false
        hiddenSince = true
        step.update { current ->
            when (current) {
                is Step.Signing -> current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY))
                is Step.Reviewing ->
                    current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
                else -> current
            }
        }
    }

    /** The screen is shown (again): the owners are read again, and a review left while hidden is quoted anew. */
    fun onShown() {
        shown = true
        if (hiddenSince) checkAgain()
        hiddenSince = false
        requoteIfHidden()
    }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) requote(current, current.account, current.level, note = null)
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        requote(review, review.account, review.level, note = STALE_QUOTE)
        return true
    }

    private fun quoteForReview(kind: ReviewKind, account: Address, level: FeeLevel) {
        if (step.value != Step.Overview) return
        val request = now?.request ?: return
        view.update { it.copy(message = null, sharing = false) }
        val quoting = Step.Quoting(kind, account, level)
        step.value = quoting
        job = viewModelScope.launch {
            val next = quoted(kind, request, account, level)
            val biometrics = describeKeyStore.biometricsOn()
            if (step.value != quoting) return@launch
            when (next) {
                is Quoted.Made -> step.value =
                    Step.Reviewing(kind, next.quote, timeSource.markNow(), UnlockStep(biometrics = biometrics))
                is Quoted.Refused -> {
                    view.update { it.copy(message = next.message) }
                    step.value = Step.Overview
                }
            }
        }
    }

    /** [review] quoted again from [account] at [level], saying [note] under the unlock once shown. */
    private fun requote(review: Step.Reviewing, account: Address, level: FeeLevel, note: String?) {
        val request = now?.request ?: return
        step.value = review.copy(refreshing = true, hidden = false, unlock = review.unlock.copy(error = null))
        job = viewModelScope.launch {
            val next = quoted(review.kind, request, account, level)
            val latest = step.value
            if (latest !is Step.Reviewing || !latest.refreshing) return@launch
            when (next) {
                // The password typed meanwhile stays: it is the same review.
                is Quoted.Made -> step.value = latest.copy(
                    quote = next.quote,
                    quotedAt = timeSource.markNow(),
                    refreshing = false,
                    unlock = latest.unlock.copy(error = note),
                )
                // Nothing to sign any more: the overview says why.
                is Quoted.Refused -> {
                    view.update { it.copy(message = next.message) }
                    step.value = Step.Overview
                }
            }
        }
    }

    /** What a quote came to. */
    private sealed interface Quoted {
        data class Made(val quote: TxQuote) : Quoted
        data class Refused(val message: String) : Quoted
    }

    private suspend fun quoted(kind: ReviewKind, request: SigningRequest, account: Address, level: FeeLevel): Quoted =
        try {
            when (kind) {
                ReviewKind.APPROVAL -> when (val quoting = quoteSafeApproval(request, account, level)) {
                    is SafeApprovalQuoting.Quoted -> Quoted.Made(TxQuote.Approval(quoting.quote))
                    is SafeApprovalQuoting.Refused -> Quoted.Refused(safeTxRefusalText(quoting.refusal, network))
                }
                ReviewKind.EXECUTION -> when (val quoting = quoteSafeExecution(request, account, level)) {
                    is SafeExecutionQuoting.Quoted -> Quoted.Made(TxQuote.Execution(quoting.quote))
                    is SafeExecutionQuoting.Refused -> Quoted.Refused(safeTxRefusalText(quoting.refusal, network))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The use cases promise not to throw; the backstop.
            Quoted.Refused(safeTxRefusalText(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), network))
        }

    private fun signHash(signing: Step.Signing, unlock: Unlock) {
        val context = now ?: return
        val request = context.request
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = signing.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = signing.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val result = withContext(NonCancellable) {
                try {
                    signSafeTx(request, signing.owner, unlock)
                } catch (e: Exception) {
                    null // the use case promises not to throw; the backstop
                }
            }
            val latest = step.value as? Step.Signing ?: return@launch
            val label = context.labelOf(signing.owner)
            when (result) {
                is SafeTxSigning.Signed -> {
                    view.update {
                        it.copy(
                            unsaved = if (result.saved) null else result.request,
                            message = if (result.saved) {
                                "Signed as $label. Share the request with the other owners, or execute it once " +
                                    "the threshold is met."
                            } else {
                                "Signed as $label, but this device's queue cannot be saved right now: the signature " +
                                    "is only on this screen. Share the request before you leave it."
                            },
                        )
                    }
                    step.value = Step.Overview
                    checkAgain()
                }
                is SafeTxSigning.NotSigned -> step.value = latest.copy(
                    signing = false,
                    unlock = latest.unlock.copy(
                        error = notSignedText(result.signing, SIGN_FAILED),
                        biometrics = latest.unlock.biometrics && biometricsStillOffered(result.signing),
                    ),
                )
                is SafeTxSigning.Refused -> {
                    view.update { it.copy(message = safeTxRefusalText(result.refusal, network)) }
                    step.value = Step.Overview
                }
                null -> step.value = latest.copy(signing = false, unlock = latest.unlock.copy(error = SIGN_FAILED))
            }
        }
    }

    private fun send(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val sending = safely {
                when (val quote = review.quote) {
                    is TxQuote.Approval -> approveSafeTxOnChain(quote.quote, unlock)
                    is TxQuote.Execution -> executeSafeTx(quote.quote, unlock)
                }
            }
            step.update { latest ->
                if (latest !is Step.Reviewing) return@update latest
                when (sending) {
                    is TransferSending.Sent -> Step.Sent(sending.hash)
                    is TransferSending.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(sending.signing, SEND_FAILED),
                            biometrics = latest.unlock.biometrics && biometricsStillOffered(sending.signing),
                        ),
                    )
                    is TransferSending.NotSent ->
                        latest.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
                    is TransferSending.RejectionUnverified -> latest.copy(
                        signing = false,
                        notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
                    )
                    null -> latest.copy(signing = false, unlock = latest.unlock.copy(error = SEND_FAILED))
                }
            }
            // Hidden while it ran and nothing was sent: the quote is from before, so it is made again.
            requoteIfHidden()
        }
    }

    /**
     * [send], to its end even when the screen is left meanwhile: a transaction signed is recorded
     * and broadcast as one step, which leaving cannot cut in half (its outcome is then not shown).
     */
    private suspend fun safely(send: suspend () -> TransferSending): TransferSending? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                // The ports promise not to throw; the backstop.
                null
            }
        }

    private suspend fun readOwners(request: SigningRequest): SafeTxOwnersReading = try {
        readSafeTxOwners(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeTxOwnersReading.Refused(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN)) // the backstop
    }

    /**
     * [reading], after [previous]: a transaction gone from the queue is said to have run when the
     * Safe's nonce passed it.
     */
    private fun queuedOf(previous: Queued, reading: SafeTxQueueReading): Queued = when (reading) {
        SafeTxQueueReading.Unreadable -> Queued.Unreadable
        is SafeTxQueueReading.Loaded -> {
            val tx = reading.transactions.firstOrNull { it.request.hash == hash }
            if (tx != null) {
                Queued.Found(tx, reading.safeNonce)
            } else {
                val nonce = when (previous) {
                    is Queued.Found -> previous.tx.request.nonce
                    is Queued.Missing -> previous.lastNonce
                    else -> null
                }
                val safeNonce = reading.safeNonce
                Queued.Missing(nonce, passed = nonce != null && safeNonce != null && nonce < safeNonce)
            }
        }
    }

    /** What the biometric prompt says is signed: the transaction's nonce, its Safe and what it does. */
    private fun promptText(context: Context): String {
        val request = context.request
        val action = safeTxActionItem(context.found.tx.action, request.nonce, context.tokens, network)
        // The address it names in full: the prompt is the last thing read before the key signs.
        val named = action.addresses.firstOrNull()?.let { ", ${it.what.lowercase()} ${it.address}" }.orEmpty()
        return "${request.nonce} of ${context.labelOf(safe)}: ${action.title}$named"
    }

    private fun render(context: Source?, view: View, step: Step): SafeTxUiState {
        val network = network.displayName
        when (step) {
            is Step.Sent -> return SafeTxUiState.Sent(network, step.hash)
            Step.Removed -> return SafeTxUiState.Removed(network)
            else -> Unit
        }
        val shown = when (context) {
            is Context -> context
            Queued.Unreadable -> return SafeTxUiState.NotQueued(network, QUEUE_UNREADABLE)
            is Queued.Missing -> return SafeTxUiState.NotQueued(network, if (context.passed) PASSED else NOT_QUEUED)
            null, Queued.Loading, is Queued.Found -> return SafeTxUiState.Loading(network)
        }
        val tx = shown.found.tx
        val request = shown.request
        val action = safeTxActionItem(tx.action, request.nonce, shown.tokens, this.network)
        val read = shown.owners as? SafeTxOwnersReading.Read
        val header = SafeHeader(
            label = shown.labels[safe],
            address = Eip55.format(safe),
            threshold = read?.let { "${it.threshold}/${it.owners.size}" },
        )
        val nonce = nonceText(request.nonce, read?.safeNonce ?: shown.found.safeNonce)
        val hashText = "0x" + request.hash.hex
        val options = shown.options
        return when (step) {
            is Step.Signing -> SafeTxUiState.Signing(
                network = network,
                safe = header,
                action = action,
                nonce = nonce,
                hash = hashText,
                owners = options.signers.map { choice(shown, it) },
                owner = Eip55.format(step.owner),
                unlock = step.unlock,
                signing = step.signing,
            )
            is Step.Reviewing -> review(shown, header, action, nonce, step)
            else -> SafeTxUiState.Overview(
                network = network,
                safe = header,
                action = action,
                nonce = nonce,
                hash = hashText,
                owners = ownersItem(shown),
                gasTerms = tx.gasTerms?.let(::gasTermsItem),
                refusal = tx.refusal?.let { safeTxRefusalText(it, this.network) },
                canSign = options.signers.isNotEmpty(),
                canApprove = options.approvers.isNotEmpty(),
                canExecute = options.executors.isNotEmpty(),
                executeNote = options.executeNote,
                quoting = step is Step.Quoting,
                share = if (view.sharing) share(request) else null,
                message = view.message,
            )
        }
    }

    private fun options(tx: DecodedSafeTx, read: SafeTxOwnersReading.Read?, held: List<Account>): Options {
        if (tx.refusal != null || read == null) return Options(emptyList(), emptyList(), emptyList(), null)
        val unconfirmed = read.owners.filter { it.held && !it.confirms }.map { it.owner }
        val short = read.confirmations < read.threshold
        // A held owner first: its sending counts as its own confirmation.
        val heldOwners = read.owners.filter { it.held }.map { it.owner }
        val candidates = heldOwners + held.map { it.address }.filter { it !in heldOwners }
        val nonce = tx.request.nonce
        val executors = if (nonce == read.safeNonce) {
            candidates.filter { read.confirmationsExecutedBy(it) >= read.threshold }
        } else {
            emptyList()
        }
        val best = candidates.maxOfOrNull { read.confirmationsExecutedBy(it) } ?: read.confirmations
        val executeNote = when {
            executors.isNotEmpty() -> null
            nonce > read.safeNonce -> "Executed only once the ${count(nonce - read.safeNonce)} before it have run: " +
                "only the Safe's next transaction can be executed."
            candidates.isEmpty() -> "No account of this wallet holds a key to send its execution."
            else -> "$best of the ${read.threshold} confirmations it needs to be executed."
        }
        return Options(
            signers = unconfirmed,
            approvers = if (short) unconfirmed else emptyList(),
            executors = executors,
            executeNote = executeNote,
        )
    }

    private fun count(n: Long) = if (n == 1L) "1 transaction" else "$n transactions"

    private fun nonceText(nonce: Long, safeNonce: Long?): String = "Safe nonce $nonce" + when {
        safeNonce == null -> ""
        nonce == safeNonce -> " · next to run"
        else -> " · ${count(nonce - safeNonce)} run before it"
    }

    private fun choice(context: Context, address: Address) =
        SignerChoice(context.labelOf(address), Eip55.format(address))

    private fun ownersItem(context: Context): OwnersItem = when (val owners = context.owners) {
        null -> OwnersItem.Checking
        is SafeTxOwnersReading.Refused -> OwnersItem.Unavailable(safeTxRefusalText(owners.refusal, network))
        is SafeTxOwnersReading.Read -> OwnersItem.Shown(
            rows = owners.owners.map { owner ->
                val (state, detail) = when (val confirmation = owner.confirmation) {
                    OwnerConfirmation.Signed -> OwnerState.SIGNED to "Signed"
                    is OwnerConfirmation.ApprovedOnChain -> OwnerState.APPROVED to "Approved on chain" +
                        confirmation.blockNumber?.let { ", verified at block ${grouped(it)}" }.orEmpty()
                    OwnerConfirmation.Missing -> OwnerState.MISSING to "Not yet"
                    is OwnerConfirmation.Unknown -> OwnerState.UNKNOWN to
                        "No signature; whether it approved on chain cannot be verified right now"
                }
                OwnerStateRow(Eip55.format(owner.owner), context.labels[owner.owner], owner.held, state, detail)
            },
            threshold = "${owners.threshold} of ${owners.owners.size} owners must confirm",
            confirmations = "${owners.confirmations} of ${owners.threshold} confirmations",
            verifiedAt = "Owners verified at block ${grouped(owners.blockNumber)}",
        )
    }

    private fun share(request: SigningRequest): ShareItem {
        val text = encodeSigningRequest(request)
        val signatures = request.signatures.size
        val carries = "The raw transaction and " + when (signatures) {
            0 -> "no signature yet"
            1 -> "1 signature"
            else -> "$signatures signatures"
        } + ": the other device decodes it and checks it against the chain itself."
        val fits = QrMatrix.fits(text.encodeToByteArray().size)
        return ShareItem(text, fits, if (fits) carries else "Too large for one QR code: share it as text. $carries")
    }

    private fun review(
        context: Context,
        header: SafeHeader,
        action: SafeTxActionItem,
        nonce: String,
        step: Step.Reviewing,
    ): SafeTxUiState.Review {
        val options = context.options
        val call = step.quote.call
        val native = network.nativeSymbol
        val ran = call.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        val allowed = if (step.kind == ReviewKind.APPROVAL) options.approvers else options.executors
        // The account under review stays offered while it is quoted, whatever the owners say since.
        val accounts = (listOf(step.account) + allowed).distinct()
        return SafeTxUiState.Review(
            network = network.displayName,
            safe = header,
            action = action,
            nonce = nonce,
            kind = step.kind,
            accounts = accounts.map { choice(context, it) },
            account = Eip55.format(step.account),
            fee = call.level,
            maxFee = exactAmount(call.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(call.level, call.transaction, call.feeBlock),
            balance = BalanceChange(
                what = "$native balance of ${context.labelOf(step.account)}, which pays the fee",
                before = exactAmount(call.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(call.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                verifiedAt = verifiedAt(call.balanceBlock),
            ),
            simulation = when (step.kind) {
                ReviewKind.APPROVAL ->
                    "The Safe's approveHash of this transaction, run as a verified call from this account $ran, " +
                        "goes through."
                ReviewKind.EXECUTION ->
                    "The whole execution — the Safe's checks of the confirmations and the nonce, its guard if it " +
                        "has one, and the call itself — run as a verified call from this account $ran, goes through."
            },
            unlock = step.unlock,
            confirmations = (step.quote as? TxQuote.Execution)?.quote?.let { confirmationsText(context, it) },
            pending = call.pendingCost.takeUnless { it.isZero }?.let {
                "Earlier transactions from this account, not mined yet, may still take up to " +
                    exactAmount(it, NATIVE_DECIMALS, native) + ": the balance after counts it."
            },
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                val what = if (step.kind == ReviewKind.APPROVAL) "approval" else "execution"
                notSentItem(it.broadcast, it.unverified, what)
            },
        )
    }

    private fun confirmationsText(context: Context, quote: SafeExecutionQuote): String =
        "Executed with: " + quote.signatures.joinToString("; ") { signature ->
            val who = context.labelOf(signature.owner)
            when (signature) {
                is SafeSignature.Ecdsa -> "$who's signature"
                is SafeSignature.ApprovedHash -> if (signature.owner == quote.executor) {
                    "$who, by sending the execution"
                } else {
                    "$who's approval on chain"
                }
            }
        }

    private companion object {
        const val SIGN_FAILED = "Serotine could not finish this signature. Nothing was queued: try again."
        const val SEND_FAILED =
            "Serotine could not finish this transaction. Check the account's balance before you send again."
        const val QUEUE_UNREADABLE =
            "This device's queue of Safe transactions cannot be read right now. Serotine keeps trying."
        const val NOT_QUEUED = "This transaction is not in this device's queue."
        const val PASSED =
            "No longer pending: the Safe's nonce has passed it, so it ran, or another transaction used its nonce."
    }
}
