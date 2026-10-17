package eth.serotine.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.BiometricUnusable
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.AccountValue
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RecipientLookup
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SendTransfer
import eth.serotine.domain.usecase.TransferQuote
import eth.serotine.domain.usecase.TransferQuoting
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.usecase.Valuation
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.balanceText
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.formatUnits
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.valuationText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Send screen's ViewModel (docs/wallet-ux.md, screens 7 and 8): a transfer from [from] on
 * [network], composed — the asset ([asset] the token asked for, null for the network's currency),
 * the recipient typed or picked and resolved ([ResolveRecipient]), the amount in token units and
 * the fee level — then quoted from verified state ([QuoteTransfer]) for review, and signed and
 * broadcast ([SendTransfer]); one signed but not broadcast is retried with its own bytes
 * ([RebroadcastTransfer]), never signed anew.
 *
 * What is signed is the quote under review, and a quote is never signed stale: it is made when
 * the review is entered, again on Refresh, when the screen is shown again after it was not, and
 * when it is older than [QUOTE_MAX_AGE] at the tap that would sign it ([timeSource] tells), and
 * it is dropped by any change of what it was made for. Its recipient's name is resolved again
 * with every quote. A price never reaches it: the amount is what was typed, in token units.
 *
 * One of the ViewModels the [WalletPassword] passes through (CLAUDE.md, *Trust*): it holds it in
 * the review's state while the field is shown, hands it to the signer in an [Unlock], and never
 * logs, saves or caches it; it is dropped once used, when the screen is not shown ([onHidden])
 * and when the screen is gone ([onCleared]).
 */
class SendViewModel(
    private val from: Address,
    asset: Address?,
    observeAccounts: ObserveAccounts,
    observeAccountValue: ObserveAccountValue,
    private val describeKeyStore: DescribeKeyStore,
    private val resolveRecipient: ResolveRecipient,
    private val quoteTransfer: QuoteTransfer,
    private val sendTransfer: SendTransfer,
    private val rebroadcastTransfer: RebroadcastTransfer,
    private val network: Network,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    /**
     * What the compose step has: the token chosen ([asset], null for the network's currency), the
     * recipient and the amount as typed, the fee level, and how often the user asked to resolve the
     * recipient again ([lookupAttempt]).
     */
    private data class Inputs(
        val asset: Address?,
        val to: String = "",
        val amount: String = "",
        val fee: FeeLevel = FeeLevel.NORMAL,
        val lookupAttempt: Int = 0,
    )

    /** What the typed recipient [text] is, as far as is known yet. */
    private sealed interface Lookup {
        val text: String

        data object Empty : Lookup {
            override val text = ""
        }

        /** A name, being resolved. */
        data class Resolving(override val text: String) : Lookup

        data class Done(override val text: String, val result: RecipientLookup) : Lookup
    }

    /** The account sent from, or why nothing can be sent from it. */
    private sealed interface Sender {
        data class From(val account: Account) : Sender

        data class Cannot(val message: String) : Sender
    }

    /** An asset the account can send, with what the compose step shows of it. */
    private data class Option(
        val asset: TransferAsset,
        val symbol: String,
        val name: String,
        val icon: String?,
        val decimals: Int,
        val balance: UInt256?,
        val balanceText: String,
        val verifiedAt: String?,
        val valuation: Valuation?,
        val warning: String?,
    ) {
        val contract: Address?
            get() = (asset as? TransferAsset.Erc20Token)?.token?.address
    }

    /** Everything the compose step is derived from. */
    private class Model(
        val sender: Sender,
        val accounts: List<Account>,
        val currency: DisplayCurrency,
        val options: List<Option>,
        val selected: Option?,
        val tokensRead: Boolean,
        val inputs: Inputs,
        val lookup: Lookup,
    )

    /** A transfer the compose step can have quoted, exactly as it has it. */
    private data class Request(
        val inputs: Inputs,
        val to: RecipientLookup.Found,
        val option: Option,
        val amount: UInt256,
    )

    /**
     * A transfer signed but not broadcast: why ([broadcast]), and the [transfer] a retry sends;
     * [unverified] when a retry's rejection could not be checked against the receipt.
     */
    private data class NotSent(val broadcast: Broadcast, val transfer: SentTransfer, val unverified: Boolean = false) {
        /**
         * Whether sending the same bytes again may change the verdict: no peer answered, or a
         * rejection could not yet be told from the first broadcast having been mined.
         */
        val canRetry: Boolean
            get() = unverified || broadcast is Broadcast.Unavailable
    }

    /** Where the flow stands. */
    private sealed interface Step {
        data class Composing(val quoting: Boolean = false, val error: String? = null) : Step

        /**
         * [quote] of [request], made at [quotedAt], under review; [hidden] once the screen stopped
         * being shown since it was made.
         */
        data class Reviewing(
            val request: Request,
            val quote: TransferQuote,
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

    // The checksum once, not on every balance reading.
    private val display = Eip55.format(from)

    private val inputs = MutableStateFlow(Inputs(asset))
    private val step = MutableStateFlow<Step>(Step.Composing())

    /** The quote, signature or broadcast in flight. */
    private var job: Job? = null

    /** Whether the screen is shown now ([onShown], [onHidden]). */
    private var shown = false

    /**
     * The typed recipient, resolved: an address at once, a name once the typing paused, so that
     * a name is not resolved for every letter of it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lookup: Flow<Lookup> = inputs
        .map { it.to.trim() to it.lookupAttempt }
        .distinctUntilChanged()
        .transformLatest { (text, _) ->
            if (text.isEmpty()) {
                emit(Lookup.Empty)
                return@transformLatest
            }
            // An address has no dot; ResolveRecipient takes anything with one as a name.
            if ('.' in text) {
                emit(Lookup.Resolving(text))
                delay(NAME_PAUSE_MS)
            }
            emit(Lookup.Done(text, lookUp(text)))
        }

    private val model: StateFlow<Model?> =
        combine(observeAccounts(), observeAccountValue(from), inputs, lookup) { list, value, inputs, lookup ->
            modelOf(list, value, inputs, lookup)
        }.stateIn(
            viewModelScope,
            // As on the account screens: back after the timeout, no old balance stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            null,
        )

    val uiState: StateFlow<SendUiState> = combine(model, step) { model, step -> render(model, step) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        SendUiState.Loading(network.displayName),
    )

    /** Chooses the asset: the token at [contract], or the network's currency (null). */
    fun onAssetChange(contract: String?) {
        val asset = contract?.let { Address.parse(it) ?: return }
        change { it.copy(asset = asset) }
    }

    /** The recipient field now reads [text]. */
    fun onToChange(text: String) = change { it.copy(to = text) }

    /** Sends to the wallet's account at [address], its EIP-55 form. */
    fun onPickAccount(address: String) = change { it.copy(to = address) }

    /** Resolves the recipient again, after a lookup that could not be verified. */
    fun onRetryLookup() = change { it.copy(lookupAttempt = it.lookupAttempt + 1) }

    /** The amount field now reads [text], in the asset's units. */
    fun onAmountChange(text: String) = change { it.copy(amount = text) }

    /**
     * Fills in the chosen asset's whole verified balance. For the network's currency that leaves
     * nothing for the fee, which the quote then says.
     */
    fun onMax() {
        val option = model.value?.selected ?: return
        val balance = option.balance ?: return
        change { it.copy(amount = balance.formatUnits(option.decimals)) }
    }

    /** Chooses the fee [level]. */
    fun onFeeChange(level: FeeLevel) = change { it.copy(fee = level) }

    /** Quotes the transfer as composed and, when the quote stands, shows it for review. */
    fun review() {
        val current = step.value as? Step.Composing ?: return
        if (current.quoting) return
        val request = model.value?.let(::requestOf) ?: return
        step.value = Step.Composing(quoting = true)
        job = viewModelScope.launch {
            val next = quoted(request, unlock = null)
            step.update { latest -> if (latest == Step.Composing(quoting = true)) next else latest }
        }
    }

    /**
     * Back from the review to the compose step: the quote and the password go. Not while signing,
     * nor after a broadcast no peer answered: those bytes may be out there, and only a retry of
     * them, never a new quote, may send them again.
     */
    fun edit() {
        val current = step.value as? Step.Reviewing ?: return
        if (current.signing || current.notSent?.canRetry == true) return
        job?.cancel()
        step.value = Step.Composing()
    }

    /** Quotes the reviewed transfer again: fresh fees, nonce and balances. */
    fun refresh() = refresh(note = null)

    /** [refresh], saying [note] under the unlock once the new quote is shown. */
    private fun refresh(note: String?) {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle) return
        step.value = current.copy(refreshing = true, hidden = false, unlock = current.unlock.copy(error = null))
        job = viewModelScope.launch {
            val next = quoted(current.request, unlock = current.unlock)
            step.update { latest ->
                when {
                    latest !is Step.Reviewing || !latest.refreshing -> latest
                    // The password typed meanwhile stays: it is the same review.
                    next is Step.Reviewing -> latest.copy(
                        request = next.request,
                        quote = next.quote,
                        quotedAt = next.quotedAt,
                        refreshing = false,
                        unlock = latest.unlock.copy(error = note),
                    )
                    else -> next
                }
            }
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

    /** Signs the reviewed transfer with the typed wallet password, and broadcasts it. */
    fun sign() {
        val current = step.value as? Step.Reviewing ?: return
        val password = current.unlock.password
        if (!current.idle || password.text.isEmpty() || requoted(current)) return
        sign(current, Unlock.WithPassword(password))
    }

    /** Signs the reviewed transfer with a fingerprint or face, and broadcasts it. */
    fun signWithBiometrics() {
        val current = step.value as? Step.Reviewing ?: return
        if (!current.idle || !current.unlock.biometrics || requoted(current)) return
        val amount = exact(current.quote.amount, current.request.option.decimals, current.request.option.symbol)
        // The recipient in full: the prompt is the last thing read before the key signs.
        sign(current, Unlock.WithBiometrics("Send $amount to ${Eip55.format(current.quote.to)}"))
    }

    /** Whether [review]'s quote is too old to sign, and so is being made again instead. */
    private fun requoted(review: Step.Reviewing): Boolean {
        if (review.quotedAt.elapsedNow() <= QUOTE_MAX_AGE) return false
        refresh(note = STALE_QUOTE)
        return true
    }

    /** Broadcasts the transfer signed but not sent again, byte for byte. */
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
     * The screen stopped being shown: the password goes (CLAUDE.md, *Trust*; rule 8), and the
     * review is quoted again when it is shown again ([onShown]). A signature already running
     * finishes, and its outcome is shown when it lands.
     */
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

    /**
     * The screen is shown (again): a review left while hidden is quoted anew, now or, when a
     * signature is running, once it lands without having sent anything.
     */
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
            val sending = safely { sendTransfer(review.quote, unlock) }
            step.update { latest ->
                if (latest !is Step.Reviewing) return@update latest
                when (sending) {
                    is TransferSending.Sent -> Step.Sent(sending.hash)
                    is TransferSending.NotSigned -> latest.copy(
                        signing = false,
                        unlock = latest.unlock.copy(
                            error = notSignedText(sending.signing),
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
     * The review of [request], quoted now, with [unlock] (a new one when null), or the compose
     * step saying why there is none. A name is resolved again first: what is signed is the
     * address the name has now, and one that moved is said, not signed.
     */
    private suspend fun quoted(request: Request, unlock: UnlockStep?): Step {
        val name = request.to.name
        var to = request.to
        if (name != null) {
            val again = lookUp(name)
            if (again !is RecipientLookup.Found || again.address != request.to.address) {
                inputs.update { it.copy(lookupAttempt = it.lookupAttempt + 1) }
                return Step.Composing(
                    error = if (again is RecipientLookup.Unavailable) {
                        "“$name” could not be resolved with verified calls again just now, so nothing can be " +
                            "signed for it: review it again in a moment."
                    } else {
                        "“$name” no longer resolves to the address shown: check the recipient."
                    },
                )
            }
            to = again // its block is this resolution's
        }
        val quoting = try {
            quoteTransfer(from, request.to.address, request.option.asset, request.amount, request.inputs.fee)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN) // the use case promises not to throw; the backstop
        }
        if (quoting !is TransferQuoting.Quoted) return Step.Composing(error = refusal(quoting, request.option))
        return Step.Reviewing(
            request.copy(to = to),
            quoting.quote,
            timeSource.markNow(),
            unlock ?: UnlockStep(biometrics = biometricsOffered()),
        )
    }

    /** Applies [change] to the compose step's inputs: only while composing, dropping a quote in flight. */
    private fun change(edit: (Inputs) -> Inputs) {
        if (step.value !is Step.Composing) return
        job?.cancel()
        inputs.update(edit)
        step.value = Step.Composing()
    }

    private suspend fun lookUp(text: String): RecipientLookup {
        // An all-caps copy upper-cases the prefix too.
        val normalized = if (text.startsWith("0X")) "0x" + text.drop(2) else text
        return try {
            resolveRecipient(normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecipientLookup.Unavailable(ReadUnavailable.UNPROVEN) // the port promises not to throw; the backstop
        }
    }

    private suspend fun biometricsOffered(): Boolean = try {
        (describeKeyStore() as? KeyStoreDescription.Ready)?.biometrics == BiometricUnlock.On
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false // the port promises not to throw; the backstop: the password still works
    }

    /**
     * [send], to its end even when the screen is left meanwhile: a transfer signed is recorded and
     * broadcast as one step, which leaving cannot cut in half (its outcome is then not shown).
     */
    private suspend fun safely(send: suspend () -> TransferSending): TransferSending? =
        withContext(NonCancellable) {
            try {
                send()
            } catch (e: Exception) {
                // The ports promise not to throw; the backstop. Under NonCancellable a cancellation
                // can only be the callee's own, which is no outcome either.
                null
            }
        }

    private fun modelOf(list: AccountList, value: AccountValue, inputs: Inputs, lookup: Lookup): Model {
        val accounts = (list as? AccountList.Loaded)?.accounts.orEmpty()
        val sender = when (list) {
            is AccountList.Unreadable -> Sender.Cannot(LIST_UNAVAILABLE)
            is AccountList.Loaded -> {
                val account = accounts.firstOrNull { it.address == from }
                when {
                    account == null -> Sender.Cannot(NOT_IN_WALLET)
                    account.kind == AccountKind.SAFE -> Sender.Cannot(SAFE)
                    account.custody != AccountCustody.InKeyStore -> Sender.Cannot(WATCH_ONLY)
                    else -> Sender.From(account)
                }
            }
        }
        val options = options(value, inputs.asset)
        return Model(
            sender = sender,
            accounts = accounts,
            currency = value.currency,
            options = options,
            selected = options.firstOrNull { it.contract == inputs.asset },
            tokensRead = value.tokensRead,
            inputs = inputs,
            lookup = lookup,
        )
    }

    /**
     * The network's currency and every token the account holds, and the token [chosen] whatever
     * its balance says: it was asked for, and its row says what is known of it.
     */
    private fun options(value: AccountValue, chosen: Address?): List<Option> {
        val native = value.native
        val nativeOption = Option(
            asset = TransferAsset.Native,
            symbol = network.nativeSymbol,
            name = network.nativeName,
            icon = network.nativeIcon,
            decimals = NATIVE_DECIMALS,
            balance = (native as? BalanceReading.Verified)?.wei?.toUInt256OrNull(),
            balanceText = native?.let { balanceText(it, network) } ?: CHECKING,
            verifiedAt = (native as? BalanceReading.Verified)?.let { block(it.blockNumber) },
            valuation = value.nativeValue,
            warning = null,
        )
        val tokens = value.tokens.mapNotNull { (holding, valuation) ->
            val token = holding.token
            val reading = holding.reading
            val verified = reading as? TokenBalanceReading.Verified
            if (token.address != chosen && (verified == null || verified.units.isZero)) return@mapNotNull null
            val symbol = displaySymbol(token.symbol)
            Option(
                asset = TransferAsset.Erc20Token(token),
                symbol = symbol,
                name = displayName(token.name),
                icon = token.icon,
                decimals = token.decimals,
                balance = verified?.let { UInt256.parse(it.units.decimal) },
                balanceText = when (reading) {
                    is TokenBalanceReading.Verified -> formatUnits(reading.units.decimal, token.decimals, symbol)
                    null, TokenBalanceReading.Syncing -> CHECKING
                    TokenBalanceReading.NotAToken -> "Not a token"
                    is TokenBalanceReading.Unavailable -> "Not verified"
                },
                verifiedAt = verified?.blockNumber?.let(::block),
                valuation = valuation,
                warning = if (holding.sharesBuiltInSymbol) "Not the built-in $symbol: another contract" else null,
            )
        }
        return listOf(nativeOption) + tokens
    }

    private fun render(model: Model?, step: Step): SendUiState {
        val network = network.displayName
        if (step is Step.Sent) return SendUiState.Sent(network, step.hash)
        model ?: return SendUiState.Loading(network)
        val account = when (val sender = model.sender) {
            is Sender.Cannot -> return SendUiState.CannotSend(network, sender.message)
            is Sender.From -> sender.account
        }
        val sender = SendFrom(account.label, display)
        return when (step) {
            is Step.Composing -> compose(model, sender, step)
            is Step.Reviewing -> review(model, sender, step)
            is Step.Sent -> SendUiState.Sent(network, step.hash)
        }
    }

    private fun compose(model: Model, sender: SendFrom, step: Step.Composing): SendUiState.Compose {
        val inputs = model.inputs
        val text = inputs.to.trim()
        // The lookup of what is typed now; one of an earlier text is no answer about it.
        val lookup = model.lookup.takeIf { it.text == text }
        val result = (lookup as? Lookup.Done)?.result
        val (note, isError) = toNote(text, lookup)
        val selected = model.selected
        val amount = amountOf(inputs.amount, selected)
        return SendUiState.Compose(
            network = network.displayName,
            from = sender,
            assets = model.options.map(::choice),
            asset = selected?.let(::choice),
            to = inputs.to,
            accounts = model.accounts.filter { it.address != from }
                .map { AccountChoice(it.label, Eip55.format(it.address)) },
            amount = inputs.amount,
            fee = inputs.fee,
            assetNote = when {
                selected != null -> null
                !model.tokensRead -> "Checking this account's tokens…"
                else -> "The token asked for is not among this account's tokens: choose another asset."
            },
            recipient = (result as? RecipientLookup.Found)?.let { recipientItem(it, model.accounts, selected) },
            toNote = note,
            toError = isError,
            canRetryLookup = result is RecipientLookup.Unavailable,
            amountError = amount.error,
            amountHint = hint(amount.units, selected, model.currency),
            canMax = selected?.balance != null && !step.quoting,
            quoting = step.quoting,
            error = step.error,
            canReview = !step.quoting && requestOf(model) != null,
        )
    }

    private fun review(model: Model, sender: SendFrom, step: Step.Reviewing): SendUiState.Review {
        val quote = step.quote
        val option = step.request.option
        val native = network.nativeSymbol
        val tokenBalance = quote.tokenBefore?.let { before ->
            val after = quote.tokenAfter ?: return@let null
            BalanceChange(
                what = "${option.symbol} balance",
                before = exact(before, option.decimals, option.symbol),
                after = exact(after, option.decimals, option.symbol),
                verifiedAt = verifiedAt(quote.tokenBalanceBlock),
            )
        }
        val nativeBalance = BalanceChange(
            what = "$native balance" + if (tokenBalance != null) ", which pays the fee" else "",
            before = exact(quote.nativeBefore, NATIVE_DECIMALS, native),
            after = "at least " + exact(quote.nativeAfterAtLeast, NATIVE_DECIMALS, native),
            verifiedAt = verifiedAt(quote.balanceBlock),
        )
        val transaction = quote.transaction
        val ran = quote.simulationBlock?.let { "at block ${grouped(it)}" } ?: "at the head"
        return SendUiState.Review(
            network = network.displayName,
            from = sender,
            amount = exact(quote.amount, option.decimals, option.symbol),
            asset = choice(option),
            recipient = recipientItem(step.request.to, model.accounts, option),
            maxFee = exact(quote.maxFee, NATIVE_DECIMALS, native),
            feeDetail = "${levelName(quote.level)} fee: ${grouped(transaction.gasLimit)} gas × at most " +
                "${transaction.maxFeePerGas.formatUnits(GWEI_DECIMALS)} gwei, from the verified fee history " +
                "up to block ${grouped(quote.feeBlock)}. Only what the block charges is paid.",
            balances = listOfNotNull(tokenBalance, nativeBalance),
            simulation = if (tokenBalance != null) {
                "The token's transfer, run as a verified call from this account $ran, goes through."
            } else {
                "The transfer, run as a verified call from this account $ran, goes through."
            },
            unlock = step.unlock,
            pending = pendingText(quote, option),
            refreshing = step.refreshing,
            signing = step.signing,
            notSent = step.notSent?.let(::notSentItem),
        )
    }

    /** What this account's earlier transfers, not mined yet, may still take, when anything. */
    private fun pendingText(quote: TransferQuote, option: Option): String? {
        val native = network.nativeSymbol
        val parts = listOfNotNull(
            quote.pendingTokenAmount?.takeUnless { it.isZero }?.let { exact(it, option.decimals, option.symbol) },
            quote.pendingCost.takeUnless { it.isZero }?.let { "up to " + exact(it, NATIVE_DECIMALS, native) },
        )
        if (parts.isEmpty()) return null
        return "Earlier transfers from this account, not mined yet, may still take ${parts.joinToString(" and ")}: " +
            "the balance after counts it."
    }

    private fun choice(option: Option) = AssetChoice(
        symbol = option.symbol,
        name = option.name,
        icon = option.icon,
        balance = option.balanceText,
        contract = option.contract?.let(Eip55::format),
        verifiedAt = option.verifiedAt,
        warning = option.warning,
    )

    private fun recipientItem(found: RecipientLookup.Found, accounts: List<Account>, asset: Option?): RecipientItem {
        val inWallet = accounts.firstOrNull { it.address == found.address }
        return RecipientItem(
            address = Eip55.format(found.address),
            name = found.name,
            resolvedAt = found.blockNumber?.let { "Resolved at finalized block ${grouped(it)}" },
            label = inWallet?.label,
            note = when (found.address) {
                from -> "The account you send from: only the fee is spent."
                asset?.contract -> "The token's own contract: nothing sent to it can ever be moved out."
                else -> null
            },
        )
    }

    /** The compose step's transfer, when it is complete and the recipient is known: what a quote is asked for. */
    private fun requestOf(model: Model): Request? {
        if (model.sender !is Sender.From) return null
        val lookup = model.lookup as? Lookup.Done ?: return null
        if (lookup.text != model.inputs.to.trim()) return null
        val to = lookup.result as? RecipientLookup.Found ?: return null
        val option = model.selected ?: return null
        val amount = amountOf(model.inputs.amount, option).units ?: return null
        return Request(model.inputs, to, option, amount)
    }

    /** What the recipient field says under [text], and whether that is an error. */
    private fun toNote(text: String, lookup: Lookup?): Pair<String?, Boolean> = when (lookup) {
        null, Lookup.Empty -> null to false
        is Lookup.Resolving -> "Resolving the name with verified calls…" to false
        is Lookup.Done -> when (val result = lookup.result) {
            is RecipientLookup.Found -> null to false
            RecipientLookup.NotAnAddress -> when {
                '.' in text -> NOT_A_NAME to true
                // Not while an address or a name is still being typed.
                text.length < ADDRESS_LENGTH && HEX_SO_FAR.matches(text) -> null to false
                !text.startsWith("0x", ignoreCase = true) && LABEL_SO_FAR.matches(text) ->
                    "A name ends in its domain, as in name.eth." to false
                else -> NOT_AN_ADDRESS to true
            }
            RecipientLookup.ChecksumMismatch -> CHECKSUM to true
            RecipientLookup.ZeroAddress -> ZERO_ADDRESS to true
            RecipientLookup.OffchainOnly -> OFFCHAIN to true
            RecipientLookup.NoAddress -> "This name has no address." to true
            is RecipientLookup.Unavailable -> nameUnavailable(result.reason) to true
        }
    }

    /** An amount typed, in [option]'s smallest units, or why it is none (null while still being typed). */
    private class Amount(val units: UInt256?, val error: String?)

    private fun amountOf(text: String, option: Option?): Amount {
        val typed = text.trim()
        if (typed.isEmpty() || option == null) return Amount(null, null)
        val units = UInt256.parseUnits(typed, option.decimals)
        return when {
            units == null -> Amount(null, amountError(typed, option))
            units.isZero -> Amount(null, "Enter an amount above zero.")
            else -> Amount(units, null)
        }
    }

    private fun amountError(typed: String, option: Option): String? {
        val fraction = typed.substringAfter('.', missingDelimiterValue = "")
        return when {
            // "5." on its way to "5.5".
            option.decimals > 0 && typed.endsWith('.') &&
                UInt256.parseUnits(typed.dropLast(1), option.decimals) != null -> null
            ',' in typed -> "Use a dot for the decimals: 0.5, not 0,5."
            DECIMAL.matches(typed) && fraction.length > option.decimals -> if (option.decimals == 0) {
                "${option.symbol} has no decimals: whole numbers only."
            } else {
                "${option.symbol} has ${option.decimals} decimals: at most ${option.decimals} digits after the dot."
            }
            DECIMAL.matches(typed) -> "That is more than any balance can be."
            else -> "Not an amount: digits, with a dot for the decimals (0.5)."
        }
    }

    /**
     * What [amount] of [option] is worth in [currency], as a hint beside the amount: the verified
     * balance's value scaled to the amount — the same price, display only, never part of the
     * amount (CLAUDE.md, *Data sources*).
     */
    private fun hint(amount: UInt256?, option: Option?, currency: DisplayCurrency): String? {
        amount ?: return null
        val valued = option?.valuation as? Valuation.Valued ?: return null
        val balance = option.balance?.takeUnless { it.isZero } ?: return null
        val share = amount.toDecimal().toDouble() / balance.toDecimal().toDouble()
        return valuationText(valued.copy(amount = valued.amount * share), currency, network)?.let { "≈ $it" }
    }

    private fun refusal(quoting: TransferQuoting, option: Option): String = when (quoting) {
        is TransferQuoting.Quoted -> error("a quote is no refusal")
        TransferQuoting.ToZeroAddress -> ZERO_ADDRESS
        TransferQuoting.ToTokenContract -> "${option.symbol}'s own contract: nothing sent to it can ever be moved " +
            "out, so it is refused."
        TransferQuoting.NothingToSend -> "There is nothing to send: enter an amount above zero."
        TransferQuoting.InsufficientFunds -> if (option.asset == TransferAsset.Native) {
            "Not enough ${network.nativeSymbol} for this amount and the most the fee can be: send less, to leave " +
                "room for the fee. Transfers not mined yet count too."
        } else {
            "Not enough ${network.nativeSymbol} for the most the fee can be. Transfers not mined yet count too."
        }
        TransferQuoting.InsufficientToken -> "This account holds less ${option.symbol} than that."
        is TransferQuoting.Infeasible -> "This transfer cannot succeed: ${quoting.detail}"
        is TransferQuoting.Reverts -> {
            val at = quoting.blockNumber?.let { " at block ${grouped(it)}" }.orEmpty()
            if (option.asset == TransferAsset.Native) {
                "The transfer reverts, run as a verified call$at: the recipient refuses it. Nothing was signed."
            } else {
                "The token's contract refuses this transfer, run as a verified call$at. Nothing was signed."
            }
        }
        TransferQuoting.NotAToken ->
            "${option.symbol}'s contract does not answer as a token: nothing can be sent with it."
        TransferQuoting.Refused -> "The light client on this device cannot quote this transfer."
        TransferQuoting.Syncing -> "The light client is not synced yet: try again in a moment."
        is TransferQuoting.Unavailable -> when (quoting.reason) {
            ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so nothing can be sent."
            ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so nothing can be quoted."
            ReadUnavailable.UNPROVEN -> "A figure of the quote could not be verified this time: try again."
            ReadUnavailable.REFUSED -> "The light client on this device cannot quote this transfer."
        }
    }

    private fun notSignedText(signing: Signing): String = when (signing) {
        Signing.WrongPassword -> "That is not the wallet password. Nothing was signed."
        Signing.Cancelled ->
            "The fingerprint or face check was dismissed. Nothing was signed: try again, or use the wallet password."
        is Signing.BiometricsUnusable -> when (signing.reason) {
            BiometricUnusable.OFF -> "A fingerprint or face cannot open this account's key: use the wallet password."
            BiometricUnusable.INVALIDATED ->
                "Biometric unlock stopped working: the enrolled fingerprints or faces changed. Use the wallet " +
                    "password; Settings → Security turns it on again."
            BiometricUnusable.LOCKED_OUT ->
                "Too many failed attempts: biometrics are locked for now. Use the wallet password."
            BiometricUnusable.SENSOR_UNAVAILABLE -> "The sensor cannot be used right now: use the wallet password."
            BiometricUnusable.NO_PROMPT -> "The fingerprint or face check could not be shown: use the wallet password."
        }
        Signing.NoKey -> "This device holds no key for this account, so nothing was signed."
        Signing.Unavailable -> "The key store cannot open this account's key on this device. Nothing was signed."
        is Signing.Signed -> SEND_FAILED // a signature is not a reason not to have signed
    }

    /** Whether the biometric prompt is still worth offering after [signing] refused one. */
    private fun biometricsStillOffered(signing: Signing): Boolean = when (signing) {
        is Signing.BiometricsUnusable -> signing.reason == BiometricUnusable.SENSOR_UNAVAILABLE ||
            signing.reason == BiometricUnusable.NO_PROMPT
        else -> true
    }

    private fun notSentItem(notSent: NotSent): NotSentItem = when (val broadcast = notSent.broadcast) {
        // Not "can never be mined" when unverified: that is a verified verdict, and this one could not be checked.
        is Broadcast.Rejected -> if (notSent.unverified) {
            NotSentItem(
                "Rejected this time (${broadcast.detail}), and whether the first broadcast reached a block after " +
                    "all cannot be verified right now. Try again sends the same signed transfer and checks again; " +
                    "do not send a new one meanwhile.",
                canRetry = true,
            )
        } else {
            NotSentItem(
                "Signed, but not sent: judged on verified state, it can never be mined as signed " +
                    "(${broadcast.detail}). Sending it again would not change that.",
                canRetry = false,
            )
        }
        is Broadcast.Refused -> NotSentItem(
            "Signed, but not sent: the light client does not relay it (${broadcast.detail}).",
            canRetry = false,
        )
        is Broadcast.Unavailable -> NotSentItem(
            "Signed, but not sent: no peer took it right now" + (broadcast.detail?.let { " ($it)" } ?: "") +
                ". Try again sends the same signed transfer, not a new one.",
            canRetry = true,
        )
        is Broadcast.Sent -> NotSentItem("Sent.", canRetry = false) // not a NotSent verdict
    }

    private fun nameUnavailable(reason: ReadUnavailable): String = when (reason) {
        ReadUnavailable.NO_ENGINE -> "There is no light client on this device, so the name cannot be resolved."
        ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the name cannot be resolved."
        ReadUnavailable.UNPROVEN -> "The name could not be resolved with verified calls this time."
        ReadUnavailable.REFUSED -> "The light client on this device cannot resolve names."
    }

    private fun levelName(level: FeeLevel): String = when (level) {
        FeeLevel.SLOW -> "Slow"
        FeeLevel.NORMAL -> "Normal"
        FeeLevel.FAST -> "Fast"
    }

    /** [units] in whole units with [decimals], exact: what the review shows and what is signed. */
    private fun exact(units: UInt256, decimals: Int, symbol: String) = "${units.formatUnits(decimals)} $symbol"

    private fun verifiedAt(block: Long?) = block?.let { "verified at block ${grouped(it)}" } ?: "verified"

    private fun block(number: Long) = "block ${grouped(number)}"

    private companion object {
        /** The oldest a quote may be at the tap that signs it; an older one is made again first. */
        val QUOTE_MAX_AGE = 60.seconds

        const val STALE_QUOTE = "The quote was over a minute old, so it was made again: check it, then sign."
        const val NATIVE_DECIMALS = 18
        const val GWEI_DECIMALS = 9
        const val ADDRESS_LENGTH = 42

        /** How long the typing must pause before a name is resolved. */
        const val NAME_PAUSE_MS = 600L
        const val CHECKING = "Checking…"
        val HEX_SO_FAR = Regex("^(0|0[xX][0-9a-fA-F]*)$")
        val LABEL_SO_FAR = Regex("^[A-Za-z0-9-]+$")
        val DECIMAL = Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$")

        const val NOT_AN_ADDRESS = "Not an address: an address is 0x and 40 hexadecimal digits."
        const val NOT_A_NAME =
            "Not an ENS name Serotine can resolve: for now, names of letters (a–z), digits and hyphens."
        const val CHECKSUM = "The capitals do not match this address's checksum: check it for a typo."
        const val ZERO_ADDRESS =
            "The zero address: nobody holds its key, so whatever is sent to it is lost. It is refused."
        const val OFFCHAIN =
            "Only an off-chain gateway gives this name's address, and Serotine asks none: it cannot be resolved here."
        const val SEND_FAILED =
            "Serotine could not finish this transfer. Check the account's balance before you send again."
        const val LIST_UNAVAILABLE =
            "Your account list cannot be read right now, so nothing can be sent. Serotine keeps trying."
        const val NOT_IN_WALLET = "This account is not in your wallet any more."
        const val SAFE = "Sending from a Safe takes a Safe transaction its owners sign, which Serotine cannot make yet."
        const val WATCH_ONLY =
            "This account is watch-only: its key is not on this device, so nothing can be sent from it."
    }
}
