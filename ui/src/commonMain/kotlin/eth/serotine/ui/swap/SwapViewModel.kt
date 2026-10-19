package eth.serotine.ui.swap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.ApprovalQuoting
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.QuoteFigure
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.SwapQuote
import eth.serotine.domain.usecase.SwapQuoting
import eth.serotine.domain.usecase.SwapSending
import eth.serotine.domain.usecase.TokenApprovalQuote
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.components.AssetOption
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.NOT_A_TOKEN
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.ROUTER_NAME
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.SwapForm
import eth.serotine.ui.components.SwapPick
import eth.serotine.ui.components.TOO_SMALL
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.amountHint
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.noRouteText
import eth.serotine.ui.components.noVenueText
import eth.serotine.ui.components.notASwapText
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.priceHintText
import eth.serotine.ui.components.priceImpactText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.routeSteps
import eth.serotine.ui.components.sellNote
import eth.serotine.ui.components.swapOptions
import eth.serotine.ui.components.toleranceError
import eth.serotine.ui.components.typedAmount
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Swap screen's ViewModel (docs/swaps.md; screens 22 and 23): a Uniswap swap from [from] on
 * [network], composed — the asset sold ([sell], the token asked for, null for the network's
 * currency), the asset bought, the amount in the sold asset's units, the slippage tolerance and
 * the fee level — then quoted from verified state ([QuoteSwap]) for review and signed and
 * broadcast ([SendSwap]). A swap whose router may not take the amount yet first gets its approval
 * ([QuoteTokenApproval], [SendTokenApproval]), its own transaction: once it is sent, the compose
 * step says so, and Review quotes the swap once a block has it. One signed but not broadcast is
 * retried with its own bytes ([RebroadcastTransfer]), never signed anew.
 *
 * What is signed is the quote under review, and a quote is never signed stale, as on Send: it is
 * made when the review is entered, again on Refresh, when the screen is shown again after it was
 * not, and when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource]
 * tells); the biometric prompt gets what is left of that age ([promptDeadline]). [SendSwap] runs
 * the swap once more before it signs, and one that no longer returns its minimum is quoted again
 * and shown, not signed. A price never reaches it: every amount is the pool's or the user's.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden]) and
 * when the screen is gone ([onCleared]). [formatTime] dates the swap's deadline in the user's own
 * time zone.
 */
class SwapViewModel(
    private val from: Address,
    sell: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val describeKeyStore: DescribeKeyStore,
    private val quoteSwap: QuoteSwap,
    private val sendSwap: SendSwap,
    private val quoteTokenApproval: QuoteTokenApproval,
    private val sendTokenApproval: SendTokenApproval,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val warmFeeHistory: WarmFeeHistory,
    private val network: Network,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    init {
        // The compose step is open: the fee history is asked for now, ready by the review.
        warmFees()
    }

    /**
     * What the compose step has: the asset sold, the asset bought (null until chosen), the amount
     * and the tolerance as typed, and the fee level.
     */
    private data class Inputs(
        val sell: SwapPick,
        val buy: SwapPick? = null,
        val amount: String = "",
        val tolerance: String = SlippageTolerance.DEFAULT.percent,
        val fee: FeeLevel = FeeLevel.NORMAL,
    )

    /** The account swapped from, or why nothing can be swapped from it. */
    private sealed interface Swapper {
        data class From(val account: Account) : Swapper

        data class Cannot(val message: String) : Swapper
    }

    /** Everything the compose step is derived from. */
    private class Model(
        val swapper: Swapper,
        val currency: DisplayCurrency,
        val sellOptions: List<AssetOption>,
        val sell: AssetOption?,
        val buyOptions: List<AssetOption>,
        val buy: AssetOption?,
        val tokensRead: Boolean,
        val inputs: Inputs,
    )

    /** A swap the compose step can have quoted, exactly as it has it. */
    private data class Request(
        val inputs: Inputs,
        val sell: AssetOption,
        val buy: AssetOption,
        val amount: UInt256,
        val tolerance: SlippageTolerance,
    )

    /** What a review signs: the approval first, or the swap itself. */
    private sealed interface Quoted {
        data class Approval(val quote: TokenApprovalQuote) : Quoted

        data class Swap(val quote: SwapQuote) : Quoted
    }

    /**
     * A transaction signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the flow stands. */
    private sealed interface Step {
        /**
         * Composing; [quoting] while a review is quoted, [error] why the last one was not, [info]
         * what to know (an approval sent), and [earlier] the transaction that holds the swap back.
         */
        data class Composing(
            val quoting: Boolean = false,
            val error: String? = null,
            val info: String? = null,
            val earlier: EarlierItem? = null,
        ) : Step

        /**
         * [quoted] of [request], made at [quotedAt], under review; [hidden] once the screen stopped
         * being shown since it was made.
         */
        data class Reviewing(
            val request: Request,
            val quoted: Quoted,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
            val sender: SendFrom,
            val refreshing: Boolean = false,
            val signing: Boolean = false,
            val notSent: NotSent? = null,
            val hidden: Boolean = false,
        ) : Step {
            val idle: Boolean
                get() = !refreshing && !signing && notSent == null
        }

        data class Sent(val hash: String) : Step
    }

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(from)

    private val inputs = MutableStateFlow(Inputs(sell?.let(SwapPick::Token) ?: SwapPick.Native))
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /** Whether the screen was hidden while the review's first quote was being made. */
    private var hiddenWhileQuoting = false

    private val model: StateFlow<Model?> =
        combine(observeAccounts(), observeAccountValue(from), inputs) { list, value, inputs ->
            modelOf(list, value, inputs)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SwapUiState> = combine(model, step) { model, step -> render(model, step) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SwapUiState.Loading(network.displayName),
    )

    /** Sells the token at [contract], or the network's currency (null). */
    fun onSellChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        change { it.copy(sell = pick, buy = it.buy.takeUnless { buy -> buy == pick }, amount = "") }
    }

    /** Buys the token at [contract], or the network's currency (null). */
    fun onBuyChange(contract: String?) {
        val pick = SwapPick.of(contract) ?: return
        change { it.copy(buy = pick) }
    }

    /** The amount field now reads [text], in the sold asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /**
     * Fills in the sold asset's whole verified balance. For the network's currency that leaves
     * nothing for the fee, which the quote then says.
     */
    fun onMax() {
        val option = model.value?.sell ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** The tolerance field now reads [text], a percentage. */
    fun onToleranceChange(text: String) = change { it.copy(tolerance = text) }

    /** Chooses the fee [level]. */
    fun onFeeChange(level: FeeLevel) = change { it.copy(fee = level) }

    /** Quotes the swap as composed and shows it, or the approval it needs first, for review. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        val model = model.value ?: return
        val request = requestOf(model) ?: return
        val account = (model.swapper as? Swapper.From)?.account ?: return
        // A transaction only waiting for a block stays said while the quote runs, and after one
        // that failed short of finding it again (a read unavailable, the light client syncing).
        val waiting = current.earlier?.takeIf { it.waiting }
        val quoting = Step.Composing(quoting = true, info = waiting?.let { current.info }, earlier = waiting)
        step.value = quoting
        hiddenWhileQuoting = false
        job = viewModelScope.launch {
            val next = quoted(request, unlock = null, SendFrom(account.label, display))
            if (step.value != quoting) return@launch
            step.value = when {
                // Made while hidden: it is from before the screen came back, so it is made again.
                next is Step.Reviewing -> next.copy(hidden = hiddenWhileQuoting)
                next is Step.Composing && next.earlier == null && waiting != null ->
                    next.copy(info = quoting.info, earlier = waiting)
                else -> next
            }
            requoteIfHidden()
        }
    }

    /**
     * Back from a review to the compose step: the quote and the password go. Not while signing, nor
     * after a broadcast no peer answered: only a retry of those bytes may send them again.
     */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent?.canRetry == true) return
        job?.cancel()
        step.value = Step.Composing()
    }

    /** Quotes the reviewed approval or swap again. */
    fun refresh() = refresh(note = null)

    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        step.value = current.copy(refreshing = true, hidden = false, unlock = current.unlock.copy(error = null))
        job = viewModelScope.launch {
            val next = quoted(current.request, unlock = current.unlock, current.sender)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays when it is the same review: not when the
                    // approval became the swap (mined meanwhile) or the other way round, which the
                    // user has not read yet.
                    next is Step.Reviewing -> latest.copy(
                        request = next.request,
                        quoted = next.quoted,
                        quotedAt = next.quotedAt,
                        refreshing = false,
                        unlock = if (next.quoted::class == latest.quoted::class) {
                            latest.unlock.copy(error = note)
                        } else {
                            latest.unlock.copy(password = WalletPassword.EMPTY, error = note)
                        },
                    )
                    else -> next
                }
            }
            requoteIfHidden()
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = step.update { current ->
        if (current is Step.Reviewing && !current.signing && current.notSent == null) {
            current.copy(unlock = current.unlock.copy(password = WalletPassword(text), error = null))
        } else {
            current
        }
    }

    /** Signs the reviewed approval or swap with the typed wallet password, and broadcasts it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /** Signs the reviewed approval or swap with a fingerprint or face, and broadcasts it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        val notAfter = promptDeadline(current.quotedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        // What is signed, in full: the prompt is the last thing read before the key signs.
        val request = current.request
        val sold = { units: UInt256 -> exactAmount(units, request.sell.decimals, request.sell.symbol) }
        val bought = { units: UInt256 -> exactAmount(units, request.buy.decimals, request.buy.symbol) }
        val prompt = when (val quoted = current.quoted) {
            is Quoted.Approval -> "Allow ${Eip55.format(quoted.quote.spender)} to take ${sold(quoted.quote.amount)}"
            is Quoted.Swap -> "Swap ${sold(quoted.quote.amountIn)} for at least ${bought(quoted.quote.minimumOut)}"
        }
        sign(current, Unlock.WithBiometrics(prompt, notAfter))
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.hidden) {
            refresh(note = null)
            return true
        }
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        refresh(note = STALE_QUOTE)
        return true
    }

    /** Broadcasts the approval or swap signed but not sent again, byte for byte. */
    fun retry() {
        val current = step.value as? Step.Reviewing ?: return
        val notSent = current.notSent ?: return
        if (current.signing || !notSent.canRetry) return
        step.value = current.copy(signing = true)
        job = viewModelScope.launch {
            val sending = safely { rebroadcastTransfer(notSent.transfer) }
            step.update { latest -> if (latest is Step.Reviewing) after(latest, sending) else latest }
        }
    }

    /** The screen stopped being shown: the password goes, and the review is quoted again when it is shown. */
    fun onHidden() {
        shown = false
        if ((step.value as? Step.Composing)?.quoting == true) hiddenWhileQuoting = true
        step.update { current ->
            if (current is Step.Reviewing) {
                current.copy(unlock = current.unlock.copy(password = WalletPassword.EMPTY), hidden = true)
            } else {
                current
            }
        }
    }

    /** The screen is shown (again): a review left while hidden is quoted anew. */
    fun onShown() {
        shown = true
        requoteIfHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) refresh()
    }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun sign(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            var noLongerMet = false
            val sending = when (val quoted = review.quoted) {
                is Quoted.Approval -> safely { sendTokenApproval(quoted.quote, unlock) }
                is Quoted.Swap -> when (val swapped = safely { sendSwap(quoted.quote, unlock) }) {
                    is SwapSending.Done -> swapped.sending
                    is SwapSending.NoLongerMet -> {
                        noLongerMet = true
                        null
                    }
                    is SwapSending.NotRun -> {
                        step.update { latest ->
                            if (latest !is Step.Reviewing) return@update latest
                            val error = notRunText(swapped.reason)
                            latest.copy(signing = false, unlock = latest.unlock.copy(error = error))
                        }
                        requoteIfHidden()
                        return@launch
                    }
                    null -> null
                }
            }
            if (noLongerMet) {
                step.update { latest -> if (latest is Step.Reviewing) latest.copy(signing = false) else latest }
                refresh(note = NO_LONGER_MET)
                return@launch
            }
            step.update { latest -> if (latest is Step.Reviewing) after(latest, sending) else latest }
            // The prompt outlived the quote's age: nothing was signed, and the quote is made again.
            if ((sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut) refresh(note = PROMPT_TIMED_OUT)
            requoteIfHidden()
        }
    }

    /**
     * Where [review] goes once its transaction's signature or broadcast came to [sending]: a swap
     * broadcast is sent; an approval broadcast is back at the compose step, which says so and
     * quotes the swap once a block has it; the rest stays in the review, saying why.
     */
    private fun after(review: Step.Reviewing, sending: TransferSending?): Step {
        val approval = review.quoted is Quoted.Approval
        return when (sending) {
            is TransferSending.Sent -> if (approval) {
                val resets = (review.quoted as Quoted.Approval).quote.resetsFirst
                Step.Composing(
                    info = if (resets) RESET_SENT else APPROVAL_SENT,
                    earlier = EarlierItem(sending.hash, "Open the approval", waiting = true),
                )
            } else {
                Step.Sent(sending.hash)
            }
            is TransferSending.NotSigned -> review.copy(
                signing = false,
                unlock = review.unlock.copy(
                    error = notSignedText(sending.signing, if (approval) APPROVAL_FAILED else SWAP_FAILED),
                    biometrics = review.unlock.biometrics && biometricsStillOffered(sending.signing),
                ),
            )
            is TransferSending.NotSent ->
                review.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
            is TransferSending.RejectionUnverified -> review.copy(
                signing = false,
                notSent = NotSent(sending.rejection, sending.transfer, unverified = true),
            )
            null -> review.copy(
                signing = false,
                unlock = review.unlock.copy(error = if (approval) APPROVAL_FAILED else SWAP_FAILED),
            )
        }
    }

    /**
     * The review of [request] quoted now, with [unlock] (a new one when null): the swap's, or the
     * approval's it needs first; or the compose step saying why there is neither.
     */
    private suspend fun quoted(request: Request, unlock: UnlockStep?, sender: SendFrom): Step {
        val readFrom = timeSource.markNow()
        val fee = request.inputs.fee
        val swapping = askSwap(request, fee)
        val quoted = when (swapping) {
            is SwapQuoting.Quoted -> Quoted.Swap(swapping.quote)
            is SwapQuoting.NeedsApproval -> when (
                val approving = askApproval(swapping, request.amount, fee)
            ) {
                is ApprovalQuoting.Quoted -> Quoted.Approval(approving.quote)
                // Allowed meanwhile (an approval mined since): the swap is quoted after all.
                is ApprovalQuoting.AlreadyAllowed -> when (val again = askSwap(request, fee)) {
                    is SwapQuoting.Quoted -> Quoted.Swap(again.quote)
                    else -> return composing(again, request)
                }
                is ApprovalQuoting.ApprovalPending -> return Step.Composing(
                    info = APPROVAL_PENDING,
                    earlier = EarlierItem(approving.hash, "Open the approval", waiting = true),
                )
                is ApprovalQuoting.EarlierSwapPending -> return Step.Composing(
                    info = earlierSwapText(request),
                    earlier = EarlierItem(approving.hash, "Open the earlier swap", waiting = true),
                )
                is ApprovalQuoting.EarlierMaybeSent -> return Step.Composing(
                    error = EARLIER_MAYBE_SENT,
                    earlier = EarlierItem(approving.hash, "Open the earlier transaction", waiting = false),
                )
                else -> return Step.Composing(error = approvalRefusal(approving, request.sell))
            }
            else -> return composing(swapping, request)
        }
        return Step.Reviewing(request, quoted, readFrom, unlock ?: UnlockStep(biometrics = biometricsOffered()), sender)
    }

    private suspend fun askSwap(request: Request, fee: FeeLevel): SwapQuoting = try {
        quoteSwap(from, request.sell.asset, request.buy.asset, request.amount, request.tolerance, fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwapQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    private suspend fun askApproval(needs: SwapQuoting.NeedsApproval, amount: UInt256, fee: FeeLevel) = try {
        quoteTokenApproval(from, needs.token, needs.spender, amount, fee)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApprovalQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
    }

    /** The compose step saying why [quoting], not a quote, came back. */
    private fun composing(quoting: SwapQuoting, request: Request): Step.Composing = when (quoting) {
        is SwapQuoting.ApprovalPending -> Step.Composing(
            info = APPROVAL_PENDING,
            earlier = EarlierItem(quoting.hash, "Open the approval", waiting = true),
        )
        is SwapQuoting.EarlierSwapPending -> Step.Composing(
            info = earlierSwapText(request),
            earlier = EarlierItem(quoting.hash, "Open the earlier swap", waiting = true),
        )
        // Short of the allowance on a second read, after an approval read it as enough.
        is SwapQuoting.NeedsApproval -> Step.Composing(error = ALLOWANCE_MOVED)
        is SwapQuoting.EarlierMaybeSent -> Step.Composing(
            error = EARLIER_MAYBE_SENT,
            earlier = EarlierItem(quoting.hash, "Open the earlier transaction", waiting = false),
        )
        else -> Step.Composing(error = swapRefusal(quoting, request))
    }

    private fun earlierSwapText(request: Request) =
        "An earlier swap of ${request.sell.symbol} is on its way and not in a block yet: this one waits for it, " +
            "since both would count on one allowance."

    /**
     * Applies [edit] to the compose step's inputs: only while composing, dropping a quote in flight
     * and the last one's verdict. A transaction of the sold asset's that is only waiting for a
     * block still holds the swap back, so it stays said while that asset does.
     */
    private fun change(edit: (Inputs) -> Inputs) {
        val current = step.value as? Step.Composing ?: return
        job?.cancel()
        val before = inputs.value.sell
        inputs.update(edit)
        val waiting = current.earlier?.takeIf { it.waiting && inputs.value.sell == before }
        step.value = Step.Composing(info = waiting?.let { current.info }, earlier = waiting)
    }

    /** [send] run to its end, the screen gone or not: a signed transaction is recorded whatever happens. */
    private suspend fun <T> safely(send: suspend () -> T): T? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                null // the use cases promise not to throw; the backstop
            }
        }

    private fun modelOf(list: AccountList, value: AccountValue, inputs: Inputs): Model {
        val swapper = when (list) {
            is AccountList.Unreadable -> Swapper.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = list.accounts.firstOrNull { it.address == from }
                when {
                    account == null -> Swapper.Cannot(NOT_IN_WALLET)
                    account.kind == AccountKind.SAFE -> Swapper.Cannot(SAFE)
                    account.custody != AccountCustody.InKeyStore -> Swapper.Cannot(WATCH_ONLY)
                    else -> Swapper.From(account)
                }
            }
        }
        val options = swapOptions(value, inputs.sell, inputs.buy, network)
        val sell = options.sell
        val buy = options.buy
        return Model(
            swapper = swapper,
            currency = value.currency,
            sellOptions = options.sellOptions,
            sell = sell,
            buyOptions = options.buyOptions,
            buy = buy,
            tokensRead = value.tokensRead,
            inputs = inputs,
        )
    }

    private fun render(model: Model?, step: Step): SwapUiState {
        val network = network.displayName
        val composing = when (step) {
            is Step.Sent -> return SwapUiState.Sent(network, step.hash)
            is Step.Reviewing -> return review(step)
            is Step.Composing -> step
        }
        model ?: return SwapUiState.Loading(network)
        val account = when (val swapper = model.swapper) {
            is Swapper.Cannot -> return SwapUiState.CannotSwap(network, swapper.message)
            is Swapper.From -> swapper.account
        }
        val inputs = model.inputs
        val amount = typedAmount(inputs.amount, model.sell)
        val form = SwapForm(
            network = network,
            from = SendFrom(account.label, display),
            sellOptions = model.sellOptions.map { it.choice() },
            sell = model.sell?.choice(),
            buyOptions = model.buyOptions.map { it.choice() },
            buy = model.buy?.choice(),
            amount = inputs.amount,
            tolerance = inputs.tolerance,
            amountError = amount.error,
            amountHint = amountHint(amount.units, model.sell, model.currency, this.network),
            canMax = model.sell?.balance != null && !composing.quoting,
            toleranceError = toleranceError(inputs.tolerance),
            note = sellNote(model.sell, model.tokensRead, "this account"),
            quoting = composing.quoting,
            error = composing.error,
            info = composing.info,
            canReview = !composing.quoting && requestOf(model) != null,
        )
        return SwapUiState.Compose(form, inputs.fee, earlier = composing.earlier)
    }

    private fun review(step: Step.Reviewing): SwapUiState = when (val quoted = step.quoted) {
        is Quoted.Approval -> approvalReview(step, quoted.quote)
        is Quoted.Swap -> swapReview(step, quoted.quote)
    }

    private fun approvalReview(step: Step.Reviewing, quote: TokenApprovalQuote): SwapUiState.ApprovalReview {
        val sell = step.request.sell
        val native = network.nativeSymbol
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return SwapUiState.ApprovalReview(
            network = network.displayName,
            from = step.sender,
            token = sell.choice(),
            amount = exactAmount(quote.amount, sell.decimals, sell.symbol),
            allowance = exactAmount(quote.allowance, sell.decimals, sell.symbol),
            spender = SpenderItem(ROUTER_NAME, Eip55.format(quote.spender)),
            resetNote = if (quote.resetsFirst) {
                "${sell.symbol} refuses to move one allowance to another, as the approval of the whole amount " +
                    "showed: this sets it to zero first, and the whole amount is approved once that is in a block."
            } else {
                null
            },
            maxFee = exactAmount(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(quote.level, quote.transaction, quote.feeBlock),
            balance = BalanceChange(
                what = "$native balance, which pays the fee",
                before = exactAmount(quote.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(quote.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                verifiedAt = verifiedAt(quote.balanceBlock),
            ),
            simulation = "The approval, run as a verified call from this account $ran, goes through. It moves " +
                "nothing: the swap is its own transaction, quoted once a block has this one.",
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "approval", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    private fun swapReview(step: Step.Reviewing, quote: SwapQuote): SwapUiState.SwapReview {
        val sell = step.request.sell
        val buy = step.request.buy
        val native = network.nativeSymbol
        val route = routeSteps(quote.route, quote.pools, sell, buy)
        val sold = sell.asset as? TransferAsset.Erc20Token
        val bought = buy.asset as? TransferAsset.Erc20Token
        val balances = listOfNotNull(
            quote.sellBefore?.let { before ->
                val after = quote.sellAfter ?: return@let null
                BalanceChange(
                    what = "${sell.symbol} balance",
                    before = exactAmount(before, sell.decimals, sell.symbol),
                    after = exactAmount(after, sell.decimals, sell.symbol),
                    verifiedAt = verifiedAt(quote.tokenBalanceBlock),
                )
            },
            quote.buyBefore?.let { before ->
                BalanceChange(
                    what = "${buy.symbol} balance",
                    before = exactAmount(before, buy.decimals, buy.symbol),
                    after = "at least " + exactAmount(before + quote.minimumOut, buy.decimals, buy.symbol),
                    verifiedAt = verifiedAt(quote.buyBalanceBlock),
                )
            },
            BalanceChange(
                what = "$native balance" + if (sold != null && bought != null) ", which pays the fee" else "",
                before = exactAmount(quote.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(
                    if (buy.asset == TransferAsset.Native) quote.nativeAfterAtLeast + quote.minimumOut else
                        quote.nativeAfterAtLeast,
                    NATIVE_DECIMALS,
                    native,
                ),
                verifiedAt = verifiedAt(quote.balanceBlock),
            ),
        )
        return SwapUiState.SwapReview(
            network = network.displayName,
            from = step.sender,
            sell = sell.choice(),
            sold = exactAmount(quote.amountIn, sell.decimals, sell.symbol),
            buy = buy.choice(),
            receive = exactAmount(quote.returnedOut, buy.decimals, buy.symbol),
            ranAt = "The swap, run as a verified call from this account " +
                (quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head") + ", returns this.",
            minimum = "at least " + exactAmount(quote.minimumOut, buy.decimals, buy.symbol),
            tolerance = "${quote.tolerance} tolerance, below QuoterV2's verified quote of " +
                exactAmount(quote.quotedOut, buy.decimals, buy.symbol),
            route = route,
            routeNote = if (route.size == 1) {
                "One Uniswap v3 pool, as the pinned factory lists it."
            } else {
                "Through WETH: two Uniswap v3 pools, as the pinned factory lists them."
            } + if (sell.asset == TransferAsset.Native || buy.asset == TransferAsset.Native) {
                " ETH goes in or out as WETH, which the router wraps or unwraps in the same transaction."
            } else {
                ""
            },
            priceImpact = priceImpactText(quote.priceImpact),
            priceHint = priceHintText(network),
            spender = SpenderItem(ROUTER_NAME, Eip55.format(quote.router)),
            // A token sold: the quote read the router's allowance as covering the amount.
            allowanceNote = sold?.let {
                "An earlier approval already lets the router take this much ${sell.symbol}: no approval is needed."
            },
            deadline = "Refused by the router after ${formatTime(quote.deadline)}: the verified head's time " +
                "and ${SWAP_DEADLINE_SECONDS / 60} minutes.",
            maxFee = exactAmount(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(quote.level, quote.transaction, quote.feeBlock),
            balances = balances,
            pending = pendingText(quote, sell),
            netNote = if (quote.netOfFees) {
                null
            } else {
                "The route was chosen by what it returns, without its network fee, which could not be had right now."
            },
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "swap", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /** What this account's earlier transactions, not mined yet, may still take, when anything. */
    private fun pendingText(quote: SwapQuote, sell: AssetOption): String? {
        val native = network.nativeSymbol
        val parts = listOfNotNull(
            quote.pendingSell?.takeUnless { it.isZero }?.let { exactAmount(it, sell.decimals, sell.symbol) },
            quote.pendingCost.takeUnless { it.isZero }?.let { "up to " + exactAmount(it, NATIVE_DECIMALS, native) },
        )
        if (parts.isEmpty()) return null
        return "Earlier transactions from this account, not mined yet, may still take " +
            "${parts.joinToString(" and ")}: the balance after counts it."
    }

    /** The compose step's swap, when it is complete: what a quote is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.swapper !is Swapper.From) return null
        val sell = model.sell ?: return null
        val buy = model.buy ?: return null
        val amount = typedAmount(model.inputs.amount, sell).units ?: return null
        val tolerance = SlippageTolerance.parsePercent(model.inputs.tolerance.trim()) ?: return null
        return Request(model.inputs, sell, buy, amount, tolerance)
    }

    private fun swapRefusal(quoting: SwapQuoting, request: Request): String = when (quoting) {
        is SwapQuoting.Quoted, is SwapQuoting.NeedsApproval, is SwapQuoting.ApprovalPending,
        is SwapQuoting.EarlierSwapPending, is SwapQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        SwapQuoting.NothingToSwap -> "There is nothing to swap: enter an amount above zero."
        SwapQuoting.NotASwap -> notASwapText(request.sell.symbol, request.buy.symbol)
        SwapQuoting.NoRoute -> noRouteText(request.sell.symbol, request.buy.symbol)
        is SwapQuoting.NoVenue -> noVenueText(quoting.contract, network)
        SwapQuoting.TooSmall -> TOO_SMALL
        SwapQuoting.InsufficientFunds -> if (request.sell.asset == TransferAsset.Native) {
            "Not enough ${network.nativeSymbol} for this amount and the most the fee can be: swap less, to leave " +
                "room for the fee. Transactions not mined yet count too."
        } else {
            "Not enough ${network.nativeSymbol} for the most the fee can be. Transactions not mined yet count too."
        }
        SwapQuoting.InsufficientToken -> "This account holds less ${request.sell.symbol} than that, with what its " +
            "transactions not mined yet still move."
        is SwapQuoting.Infeasible -> "This swap cannot succeed: ${quoting.detail}"
        is SwapQuoting.Reverts -> {
            val at = quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            "The swap reverts, or returns less than its minimum, run as a verified call$at. Nothing was signed."
        }
        SwapQuoting.NotAToken -> NOT_A_TOKEN
        SwapQuoting.Refused -> "The light client on this device cannot quote this swap."
        SwapQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is SwapQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
    }

    private fun approvalRefusal(quoting: ApprovalQuoting, sell: AssetOption): String = when (quoting) {
        is ApprovalQuoting.Quoted, is ApprovalQuoting.AlreadyAllowed, is ApprovalQuoting.ApprovalPending,
        is ApprovalQuoting.EarlierSwapPending, is ApprovalQuoting.EarlierMaybeSent,
        -> error("not a refusal said as text")
        ApprovalQuoting.InsufficientFunds ->
            "Not enough ${network.nativeSymbol} for the most the approval's fee can be."
        is ApprovalQuoting.Infeasible -> "The approval cannot succeed: ${quoting.detail}"
        is ApprovalQuoting.Reverts -> "${sell.symbol}'s contract refuses the approval, run as a verified call" +
            (quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()) + ". Nothing was signed."
        ApprovalQuoting.NotAToken -> "${sell.symbol}'s contract does not answer as a token."
        ApprovalQuoting.Refused -> "The light client on this device cannot quote the approval."
        ApprovalQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is ApprovalQuoting.Unavailable -> unavailableText(quoting.reason, quoting.figure)
    }

    private fun unavailableText(reason: ReadUnavailable, figure: QuoteFigure?) =
        when (reason) {
            ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be swapped."
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
            ReadUnavailable.UNPROVEN -> "${figureText(figure, "of the quote")} could not be verified this time: " +
                "try again."
            ReadUnavailable.REFUSED -> "The light client on this device cannot quote this swap."
        }

    private fun notRunText(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.UNPROVEN -> "The swap could not be run again before signing: nothing was signed. Try again."
        else -> "The swap cannot be run again on this device right now: nothing was signed."
    }

    private fun warmFees() {
        viewModelScope.launch {
            try {
                warmFeeHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing rests on it: the quote asks for the fees itself.
            }
        }
    }

    private suspend fun biometricsOffered(): Boolean = try {
        biometricsOn(describeKeyStore())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the use case promises not to throw; the backstop: the password still works
    }

    private companion object {
        const val APPROVAL_SENT = "Your approval is on its way: once a block has it, Review quotes the swap."
        const val RESET_SENT = "Your approval of zero is on its way: once a block has it, Review asks for the " +
            "approval of the whole amount."
        const val APPROVAL_PENDING =
            "An approval of this token is on its way and not in a block yet: once a block has it, Review goes on."
        const val ALLOWANCE_MOVED =
            "The router's allowance changed while the swap was quoted: nothing was signed. Review again."
        const val NO_LONGER_MET =
            "Run again just before signing, the swap no longer returned its minimum: it was quoted again. Check it."
        const val EARLIER_MAYBE_SENT = "An earlier transaction from this account may still be on its way: no peer " +
            "answered when it was broadcast, and no block has it yet. Try it again from its status rather than " +
            "sign a new one, which could pay twice."
        const val SWAP_FAILED =
            "Serotine could not finish this swap. Check the account's balances before you swap again."
        const val APPROVAL_FAILED = "Serotine could not finish this approval. Try again."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be swapped. Serotine keeps trying."
        const val NOT_IN_WALLET = "This account is not in your wallet any more."
        const val SAFE = "A Safe's swap is proposed as Safe transactions its owners sign: Swap on the Safe's " +
            "own screen proposes one."
        const val WATCH_ONLY =
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it."
    }
}
