package eth.serotine.ui.coworder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.CowCancellationQuote
import eth.serotine.domain.usecase.CowCancellationQuoting
import eth.serotine.domain.usecase.CowOrderReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveCowOrder
import eth.serotine.domain.usecase.ObserveCowOrderLife
import eth.serotine.domain.usecase.QuoteCowCancellation
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SendCowCancellation
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NATIVE_DECIMALS
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.PROMPT_TOO_LATE
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.feeDetail
import eth.serotine.ui.components.figureText
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.components.verifiedAt
import eth.serotine.ui.platform.formatDateTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The status of the CoW order [uid] on [network] (docs/swaps.md, *Proving an order's end*): the
 * order as the record of orders has it ([ObserveCowOrder]), its end as the chain proves it, and,
 * while it is live, CoW's own word on it ([ObserveCowOrderLife]), labelled as CoW's and never a
 * fill.
 *
 * - While it may fill, it can be cancelled on chain: [cancel] quotes the cancellation
 *   ([QuoteCowCancellation]) for review, and it is signed and broadcast ([SendCowCancellation]) as
 *   any transaction, never signed stale (the quote is made again on Refresh, when the screen is
 *   shown again, and when it is older than [QUOTE_MAX_AGE] at the tap that signs, by
 *   [timeSource]). One signed and not broadcast is retried with its own bytes
 *   ([RebroadcastTransfer]).
 * - Once it is proven not to have swapped, the same swap on Uniswap is offered: Swap opened filled
 *   in, a navigation the screen makes ([CowOrderUiState.Shown.uniswapAgain]).
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): held in the
 * review's state while the field is shown, handed to the signer in an [Unlock], never logged,
 * saved or cached; dropped once used, when the screen is not shown ([onHidden]) and when it is
 * gone. [clock] (unix seconds) says whether the order's validity has passed; [formatTime] dates in
 * the user's own time zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CowOrderViewModel(
    private val uid: CowOrderUid,
    observeCowOrder: ObserveCowOrder,
    observeCowOrderLife: ObserveCowOrderLife,
    observeAccounts: ObserveAccounts,
    private val describeKeyStore: DescribeKeyStore,
    private val quoteCowCancellation: QuoteCowCancellation,
    private val sendCowCancellation: SendCowCancellation,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val network: Network,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val clock: () -> Long = ::epochSecondsNow,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    /**
     * A transaction signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable || transfer.maybeSent
    }

    /** Where the screen stands. */
    private sealed interface Step {
        /**
         * The order; [quoting] while its cancellation is quoted, [error] why the last one was not,
         * and [cancellation] the hash of one sent and not mined yet.
         */
        data class Viewing(
            val quoting: Boolean = false,
            val error: String? = null,
            val cancellation: String? = null,
        ) : Step

        /** [quote] under review, made at [quotedAt]; [hidden] once the screen stopped being shown since. */
        data class Reviewing(
            val quote: CowCancellationQuote,
            val quotedAt: TimeMark,
            val unlock: UnlockStep,
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

    /** The order, the account's name for its account, and CoW's word while it is live. */
    private data class Model(val reading: CowOrderReading, val label: String?, val life: CowOrderLife?)

    private val step = MutableStateFlow<Step>(Step.Viewing())

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    private val order = observeCowOrder(uid)

    private val model: StateFlow<Model?> = combine(
        order,
        observeAccounts(),
        // CoW is asked only while the order may still fill: once its end is proven, the chain says it.
        order.map { (it as? CowOrderReading.Found)?.record?.live == true }
            .distinctUntilChanged()
            .flatMapLatest { live -> if (live) observeCowOrderLife(uid) else flowOf(null) },
    ) { reading, accounts, life ->
        val record = (reading as? CowOrderReading.Found)?.record
        val label = (accounts as? AccountList.Loaded)?.accounts?.firstOrNull { it.address == record?.account }?.label
        Model(reading, label, life)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        null,
    )

    val uiState: StateFlow<CowOrderUiState> = combine(model, step) { model, step -> render(model, step) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        CowOrderUiState.Loading(network.displayName),
    )

    /** Quotes the order's cancellation on chain and shows it for review. */
    fun cancel() {
        val current = step.value as? Step.Viewing ?: return
        if (current.quoting) return
        val record = (model.value?.reading as? CowOrderReading.Found)?.record ?: return
        if (!record.live) return
        step.value = current.copy(quoting = true, error = null)
        job = viewModelScope.launch {
            val next = quoted(record, unlock = null)
            step.update { latest -> if (latest is Step.Viewing && latest.quoting) next else latest }
        }
    }

    /**
     * Back from the review to the order: the quote and the password go. Not while signing, nor
     * after a broadcast no peer answered.
     */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent?.canRetry == true) return
        job?.cancel()
        step.value = Step.Viewing()
    }

    /** Quotes the reviewed cancellation again. */
    fun refresh() = refresh(note = null)

    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        step.value = current.copy(refreshing = true, hidden = false, unlock = current.unlock.copy(error = null))
        job = viewModelScope.launch {
            val next = quoted(current.quote.order, unlock = current.unlock)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    next is Step.Reviewing -> latest.copy(
                        quote = next.quote,
                        quotedAt = next.quotedAt,
                        refreshing = false,
                        unlock = latest.unlock.copy(error = note),
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

    /** Signs the cancellation with the typed wallet password, and broadcasts it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /** Signs the cancellation with a fingerprint or face, and broadcasts it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        val notAfter = promptDeadline(current.quotedAt) ?: return refresh(note = PROMPT_TOO_LATE)
        // What is signed, in full: the prompt is the last thing read before the key signs.
        sign(current, Unlock.WithBiometrics("Cancel CoW order ${short(current.quote.order.uid)} on chain", notAfter))
    }

    /** Broadcasts the cancellation signed but not sent again, byte for byte. */
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

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun requoteIfHidden() {
        val current = step.value as? Step.Reviewing ?: return
        if (shown && current.hidden && current.idle) refresh()
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

    private fun sign(review: Step.Reviewing, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        val unlocked = review.unlock.copy(password = WalletPassword.EMPTY, error = null)
        step.value = review.copy(signing = true, unlock = unlocked)
        job = viewModelScope.launch {
            val sending = safely { sendCowCancellation(review.quote, unlock) }
            step.update { latest -> if (latest is Step.Reviewing) after(latest, sending) else latest }
            // The prompt outlived the quote's age: nothing was signed, and the quote is made again.
            if ((sending as? TransferSending.NotSigned)?.signing == Signing.TimedOut) refresh(note = PROMPT_TIMED_OUT)
            requoteIfHidden()
        }
    }

    /** Where [review] goes once the cancellation's signature or broadcast came to [sending]. */
    private fun after(review: Step.Reviewing, sending: TransferSending?): Step = when (sending) {
        is TransferSending.Sent -> Step.Sent(sending.hash)
        is TransferSending.NotSigned -> review.copy(
            signing = false,
            unlock = review.unlock.copy(
                error = notSignedText(sending.signing, CANCEL_FAILED),
                biometrics = review.unlock.biometrics && biometricsStillOffered(sending.signing),
            ),
        )
        is TransferSending.NotSent ->
            review.copy(signing = false, notSent = NotSent(sending.broadcast, sending.transfer))
        is TransferSending.RejectionUnverified ->
            review.copy(signing = false, notSent = NotSent(sending.rejection, sending.transfer, unverified = true))
        null -> review.copy(signing = false, unlock = review.unlock.copy(error = CANCEL_FAILED))
    }

    /**
     * The review of [record]'s cancellation quoted now, with [unlock] (a new one when null), or the
     * order saying why not.
     */
    private suspend fun quoted(record: CowOrderRecord, unlock: UnlockStep?): Step {
        val readFrom = timeSource.markNow()
        val quoting = try {
            quoteCowCancellation(record, FeeLevel.NORMAL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CowCancellationQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw
        }
        return when (quoting) {
            is CowCancellationQuoting.Quoted ->
                Step.Reviewing(quoting.quote, readFrom, unlock ?: UnlockStep(biometrics = biometricsOffered()))
            is CowCancellationQuoting.CancellationPending -> Step.Viewing(cancellation = quoting.hash)
            else -> Step.Viewing(error = refusal(quoting))
        }
    }

    private fun refusal(quoting: CowCancellationQuoting): String = when (quoting) {
        is CowCancellationQuoting.Quoted, is CowCancellationQuoting.CancellationPending ->
            error("not a refusal said as text")
        CowCancellationQuoting.AlreadyEnded ->
            "The settlement already counts this order as filled or cancelled: there is nothing to cancel."
        CowCancellationQuoting.ValidityOver -> "Its validity is over: nothing can fill it any more, so there is " +
            "nothing to cancel. Its end is proven once a finalized block is past it."
        CowCancellationQuoting.InsufficientFunds ->
            "Not enough ${network.nativeSymbol} for the most the cancellation's fee can be."
        is CowCancellationQuoting.Infeasible -> "The cancellation cannot succeed: ${quoting.detail}"
        is CowCancellationQuoting.Reverts -> "The cancellation reverts, run as a verified call" +
            (quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()) + ". Nothing was signed."
        CowCancellationQuoting.Refused -> "The light client on this device cannot quote the cancellation."
        CowCancellationQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is CowCancellationQuoting.Unavailable -> when (quoting.reason) {
            ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be cancelled."
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
            ReadUnavailable.UNPROVEN ->
                "${figureText(quoting.figure, "of the cancellation")} could not be verified this time: try again."
            ReadUnavailable.REFUSED -> "The light client on this device cannot quote the cancellation."
        }
        is CowCancellationQuoting.EarlierMaybeSent -> "An earlier transaction from this account may still be on " +
            "its way: no peer answered when it was broadcast. Try it again from its status rather than sign a " +
            "new one."
    }

    private fun render(model: Model?, step: Step): CowOrderUiState {
        val network = network.displayName
        if (step is Step.Sent) return CowOrderUiState.CancelSent(network, step.hash)
        model ?: return CowOrderUiState.Loading(network)
        val record = when (val reading = model.reading) {
            is CowOrderReading.Found -> reading.record
            CowOrderReading.NotHere -> return CowOrderUiState.Missing(network, NOT_HERE)
            CowOrderReading.Unreadable -> return CowOrderUiState.Missing(network, UNREADABLE)
        }
        val from = SendFrom(model.label ?: "This account", Eip55.format(record.account))
        val settlement = SpenderItem(SETTLEMENT_NAME, Eip55.format(record.settlement))
        return when (step) {
            is Step.Reviewing -> review(step, from, settlement)
            is Step.Viewing -> shown(record, model.life, step, from, settlement)
            is Step.Sent -> CowOrderUiState.CancelSent(network, step.hash)
        }
    }

    private fun shown(
        record: CowOrderRecord,
        life: CowOrderLife?,
        step: Step.Viewing,
        from: SendFrom,
        settlement: SpenderItem,
    ): CowOrderUiState.Shown {
        val sold = (record.sell as TransferAsset.Erc20Token).token
        val bought = record.buy
        val boughtSymbol = (bought as? TransferAsset.Erc20Token)?.token?.symbol ?: network.nativeSymbol
        val boughtDecimals = (bought as? TransferAsset.Erc20Token)?.token?.decimals ?: NATIVE_DECIMALS
        val boughtIcon = (bought as? TransferAsset.Erc20Token)?.token?.icon ?: NATIVE_ICON
        val buy = { units: UInt256 -> exactAmount(units, boughtDecimals, boughtSymbol) }
        val until = formatTime(record.order.validTo)
        val (state, end) = when (val proven = record.end) {
            null -> OrderState.LIVE to if (clock() <= record.order.validTo) {
                "Not proven yet: it may fill until $until. Its end is read from this account's verified logs, " +
                    "never from CoW."
            } else {
                "Its validity passed at $until. Whether it filled is proven once a finalized block is past it, a " +
                    "quarter of an hour or so, from this account's verified logs."
            }
            is CowOrderEnd.Filled -> OrderState.FILLED to "Filled in block ${grouped(proven.block)}: it bought " +
                "${buy(proven.trade.buyAmount)}, as the settlement's trade in this account's verified logs says."
            is CowOrderEnd.Unfilled -> if (proven.cancelled) {
                OrderState.CANCELLED to "Cancelled on chain in block ${grouped(proven.endBlock)}: it never filled, " +
                    "as this account's verified logs prove."
            } else {
                OrderState.NOT_FILLED to "Not filled: no trade for it in this account's verified logs up to block " +
                    "${grouped(proven.endBlock)}, a finalized block past its validity."
            }
            CowOrderEnd.NotProvable -> OrderState.NOT_PROVABLE to "Its end cannot be proven on this device: this " +
                "account's history is not followed from early enough, or no longer. The balance shows what happened."
        }
        val filled = record.end as? CowOrderEnd.Filled
        return CowOrderUiState.Shown(
            network = network.displayName,
            from = from,
            sold = exactAmount(record.order.sellAmount, sold.decimals, sold.symbol),
            soldSymbol = sold.symbol,
            soldIcon = sold.icon,
            bought = filled?.let { buy(it.trade.buyAmount) } ?: ("at least " + buy(record.order.buyAmount)),
            boughtSymbol = boughtSymbol,
            boughtIcon = boughtIcon,
            state = state,
            end = end,
            held = if (record.live) {
                "The ${sold.symbol} it sells is held back from other sends and swaps until it fills or can no " +
                    "longer fill."
            } else {
                null
            },
            cow = life?.let(::lifeText),
            placement = placementText(record.placement),
            validUntil = "Valid until $until: the verified head's time when it was signed, and 5 minutes.",
            settlement = settlement,
            uid = record.uid.hex,
            canCancel = record.live && clock() <= record.order.validTo,
            cancellation = step.cancellation,
            uniswapAgain = if (state == OrderState.NOT_FILLED || state == OrderState.CANCELLED) {
                SwapAgain(
                    from = record.account.hex,
                    sell = sold.address.hex,
                    buy = (bought as? TransferAsset.Erc20Token)?.token?.address?.hex,
                    amount = record.order.sellAmount.formatUnits(sold.decimals),
                )
            } else {
                null
            },
            error = step.error,
            quoting = step.quoting,
        )
    }

    private fun review(step: Step.Reviewing, from: SendFrom, settlement: SpenderItem): CowOrderUiState.CancelReview {
        val call = step.quote.call
        val native = network.nativeSymbol
        val ran = call.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return CowOrderUiState.CancelReview(
            network = network.displayName,
            from = from,
            uid = step.quote.order.uid.hex,
            settlement = settlement,
            maxFee = exactAmount(call.maxFee, NATIVE_DECIMALS, native),
            feeDetail = feeDetail(call.level, call.transaction, call.feeBlock),
            balance = BalanceChange(
                what = "$native balance, which pays the fee",
                before = exactAmount(call.nativeBefore, NATIVE_DECIMALS, native),
                after = "at least " + exactAmount(call.nativeAfterAtLeast, NATIVE_DECIMALS, native),
                verifiedAt = verifiedAt(call.balanceBlock),
            ),
            simulation = "The cancellation, run as a verified call from this account $ran, goes through. Once a " +
                "block has it, nothing can fill the order, and its end is proven once that block is finalized.",
            unlock = step.unlock,
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let {
                notSentItem(it.broadcast, it.unverified, what = "cancellation", maybeSent = it.transfer.maybeSent)
            },
        )
    }

    /** CoW's word on the order's off-chain life, labelled as CoW's. */
    private fun lifeText(life: CowOrderLife): String = "CoW says, not verified: " + when (life) {
        CowOrderLife.Open -> "the order is open in its order book, in the auction."
        CowOrderLife.Expired -> "the order expired in its order book."
        CowOrderLife.Cancelled -> "the order is cancelled in its order book."
        CowOrderLife.Closed -> "the order is no longer open in its order book."
        CowOrderLife.Unknown -> "its order book does not know this order."
        is CowOrderLife.Unavailable -> when (life.why) {
            CowUnavailable.UNDECODABLE -> "an answer this version cannot read."
            else -> "no answer right now."
        }
    }

    private fun placementText(placement: CowPlacement): String = when (placement) {
        is CowPlacement.Accepted -> "CoW took the order when it was sent."
        is CowPlacement.Refused -> "CoW refused the order when it was sent (${placement.errorType}). It is signed " +
            "all the same, so it counts as live until its end is proven."
        is CowPlacement.Unclear, CowPlacement.Attempted ->
            "CoW's answer did not come, or could not be read, when the order was sent: it may be with CoW."
    }

    /** [send] run to its end, the screen gone or not: a signed transaction is recorded whatever happens. */
    private suspend fun <T> safely(send: suspend () -> T): T? = withContext(NonCancellable) {
        try {
            send()
        } catch (e: Exception) {
            null // the use cases promise not to throw; the backstop
        }
    }

    private suspend fun biometricsOffered(): Boolean = try {
        biometricsOn(describeKeyStore())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the use case promises not to throw; the backstop: the password still works
    }

    /** The order's UID as a prompt names it: its first and last digits. */
    private fun short(uid: CowOrderUid): String = uid.hex.let { it.take(10) + "…" + it.takeLast(8) }

    private companion object {
        const val NATIVE_ICON = "eth"
        const val SETTLEMENT_NAME = "CoW's settlement contract"
        const val NOT_HERE = "This order is not recorded on this device: it was signed elsewhere, or its record " +
            "was dropped a week after its end was proven."
        const val UNREADABLE = "The record of this device's CoW orders cannot be read right now."
        const val CANCEL_FAILED = "Serotine could not finish this cancellation. Try again."
    }
}
