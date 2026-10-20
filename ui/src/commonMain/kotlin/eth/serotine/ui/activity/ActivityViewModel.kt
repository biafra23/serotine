package eth.serotine.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.SafeHistory
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Activity screen's ViewModel (docs/wallet-ux.md, screen 25; issue #85): the transactions this
 * device sent for the account at [address] ([ObserveAccountHistory]) and, for a Safe of the
 * wallet, its own events on chain ([ObserveSafeHistory]), with the names the wallet gives the
 * addresses they concern ([ObserveAccounts]), as [ActivityUiState]. An event of a transaction
 * listed as sent is left out: its row says more. [formatTime] dates each in the user's own time
 * zone. No intents: a row opens its transaction's status, which is navigation.
 */
class ActivityViewModel(
    private val address: Address,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    /** The Safe's history while the account is a Safe of the wallet; null for any other. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val safeHistory = observeAccounts()
        .map { list ->
            (list as? AccountList.Loaded)?.accounts?.any { it.address == address && it.kind == AccountKind.SAFE }
        }
        .distinctUntilChanged()
        .flatMapLatest { safe -> if (safe == true) observeSafeHistory(address) else flowOf(null) }

    val uiState: StateFlow<ActivityUiState> =
        combine(observeAccountHistory(address), observeAccounts(), safeHistory, ::render).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            ActivityUiState.Loading(shortAddress(Eip55.format(address))),
        )

    private fun render(history: HistoryList, list: AccountList, safe: SafeHistory?): ActivityUiState {
        val names = (list as? AccountList.Loaded)?.accounts.orEmpty().associate { it.address to it.label }
        val title = names[address] ?: shortAddress(Eip55.format(address))
        val entries = when (history) {
            HistoryList.Unreadable -> return ActivityUiState.Unavailable(title, UNREADABLE)
            is HistoryList.Loaded -> history.entries
        }
        val sent = entries.map { Listed(row(it, names), it.outcome.blockOrNull()) }
        if (safe == null) return ActivityUiState.Shown(title, sent.map { it.row }, COVERAGE)
        // Only a row that names its block says what its events say: one no block was seen for (a
        // transaction mined while the app was closed) gets the verified event beside it.
        val seenInBlocks = entries.filter { it.outcome.blockOrNull() != null }.mapTo(HashSet()) { it.transfer.hash }
        val events = (safe as? SafeHistory.Read)?.events.orEmpty()
            .filterNot { it.transactionHash in seenInBlocks && it.event.saidBySentRow() }
            .sortedWith(compareByDescending<SafeChainEvent> { it.blockNumber }.thenByDescending { it.logIndex })
            .map { eventRow(it, names) }
        val deployedHere = entries.any { (it.transfer.action as? SentAction.SafeDeployment)?.safe == address }
        return ActivityUiState.Shown(
            title = title,
            rows = merged(sent, events),
            coverage = safeCoverage(safe, deployedHere),
            empty = "Nothing listed yet.",
        )
    }

    /**
     * What the sent row of a transaction that logged this event says once it names the block: its
     * execution, its approval, its deployment. A failure, and any change of the Safe's settings,
     * it does not.
     */
    private fun SafeEvent.saidBySentRow(): Boolean = when (this) {
        is SafeEvent.Executed -> succeeded
        is SafeEvent.Approved, is SafeEvent.SetUp -> true
        else -> false
    }

    /**
     * The sent rows (newest first) and the [events] (newest first) in one list, newest block
     * first. A sent row no block was seen for goes with the newest row sent before it that has one
     * (at the top when none has), and before the events of that row's block.
     */
    private fun merged(sent: List<Listed>, events: List<Pair<ActivityRow, Long>>): List<ActivityRow> {
        val sentBlocks = ArrayList<Long>(sent.size)
        for ((at, listed) in sent.withIndex()) {
            sentBlocks += listed.block ?: sent.drop(at + 1).firstNotNullOfOrNull { it.block }?.let { it + 1 }
                ?: Long.MAX_VALUE
        }
        val all = sent.mapIndexed { at, listed -> listed.row to sentBlocks[at] } + events
        // Stable: rows of one block keep the order they came in, the sent ones first.
        return all.sortedByDescending { it.second }.map { it.first }
    }

    /** What the list covers for a Safe: the sent transactions, and its events as far as [safe] says. */
    private fun safeCoverage(safe: SafeHistory, deployedHere: Boolean): String {
        val events = when (safe) {
            SafeHistory.NotFollowed -> "Its events on chain are listed once the light client is synced."
            is SafeHistory.Checking -> "Its events on chain from block ${grouped(safe.start)} on are being checked."
            SafeHistory.Unavailable -> "Its events on chain cannot be read right now."
            is SafeHistory.Read -> {
                val since = if (deployedHere) "when it was deployed from this device" else "when it was added here"
                val checked = "Its events on chain from block ${grouped(safe.start)} on, $since"
                if (safe.complete) {
                    "$checked."
                } else {
                    "$checked: checked from block ${grouped(safe.checked.first)} so far, the blocks before " +
                        "are still being checked."
                }
            }
        }
        return "Transactions sent from this device. $events Token transfers are not listed yet."
    }

    /** A Safe's [event] as a row, and its block. */
    private fun eventRow(event: SafeChainEvent, names: Map<Address, String>): Pair<ActivityRow, Long> {
        fun named(other: Address): String {
            val short = shortAddress(Eip55.format(other))
            return names[other]?.let { "$it · $short" } ?: short
        }
        val (title, other, detail) = when (val what = event.event) {
            is SafeEvent.SetUp ->
                Triple("Set up", address, "${what.threshold} of ${owners(what.owners.size.toLong())} must sign")
            is SafeEvent.Executed -> Triple(
                if (what.succeeded) "Executed a Safe transaction" else "A Safe transaction failed",
                address,
                "Safe transaction ${shortHash(what.safeTxHash)}",
            )
            is SafeEvent.Received -> Triple(
                "Received ${amount(what.amount, TransferAsset.Native)}",
                what.from,
                "From ${named(what.from)}",
            )
            is SafeEvent.Approved -> Triple("Approved a Safe transaction", what.owner, "By ${named(what.owner)}")
            is SafeEvent.OwnerAdded -> Triple("Owner added", what.owner, named(what.owner))
            is SafeEvent.OwnerRemoved -> Triple("Owner removed", what.owner, named(what.owner))
            is SafeEvent.ThresholdChanged ->
                Triple("Threshold changed", address, "${owners(what.threshold)} must sign now")
            is SafeEvent.ModuleEnabled -> Triple("Module enabled", what.module, named(what.module))
            is SafeEvent.ModuleDisabled -> Triple("Module disabled", what.module, named(what.module))
            is SafeEvent.GuardChanged -> if (what.guard == Address.ZERO) {
                Triple("Guard removed", address, "No guard checks its transactions now")
            } else {
                Triple("Guard set", what.guard, named(what.guard))
            }
            is SafeEvent.FallbackHandlerChanged -> if (what.handler == Address.ZERO) {
                Triple("Fallback handler removed", address, "No fallback handler now")
            } else {
                Triple("Fallback handler set", what.handler, named(what.handler))
            }
            is SafeEvent.ModuleExecuted -> Triple(
                if (what.succeeded) "A module ran a transaction" else "A module's transaction failed",
                what.module,
                "Module ${named(what.module)}",
            )
            is SafeEvent.MessageSigned -> Triple("Signed a message", address, "Message ${shortHash(what.messageHash)}")
        }
        val (status, state) = statusOf(event.event)
        val row = ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = title,
            detail = detail,
            about = Eip55.format(other),
            time = "Block ${grouped(event.blockNumber)}",
            status = status,
            state = state,
            opens = null,
        )
        return row to event.blockNumber
    }

    /**
     * How an event's row is marked: failed for a call that reverted; a warning for what can move
     * the Safe's funds without its owners (CLAUDE.md, *Trust*): a module enabled, or a fallback
     * handler other than the pinned one or none.
     */
    private fun statusOf(event: SafeEvent): Pair<String, ActivityState> = when {
        (event as? SafeEvent.Executed)?.succeeded == false ||
            (event as? SafeEvent.ModuleExecuted)?.succeeded == false -> FAILED_ON_CHAIN to ActivityState.FAILED
        event is SafeEvent.ModuleEnabled -> MODULE_WARNING to ActivityState.WARNING
        event is SafeEvent.FallbackHandlerChanged && !PinnedFallbackHandlers.allowed(network, event.handler) ->
            HANDLER_WARNING to ActivityState.WARNING
        else -> ON_CHAIN to ActivityState.DONE
    }

    /** [count] owners, as a sentence says it: "1 owner", "2 owners". */
    private fun owners(count: Long): String = if (count == 1L) "1 owner" else "$count owners"

    /** A 32-byte hash as screens shorten it, as they do an address: "0xab12…cd34". */
    private fun shortHash(hash: String): String = shortAddress(hash)

    private fun SentOutcome.blockOrNull(): Long? = when (this) {
        is SentOutcome.Included -> blockNumber
        is SentOutcome.Finalized -> blockNumber
        SentOutcome.NotSeen, SentOutcome.NonceUsed -> null
    }

    /** A sent transaction's [row], and the [block] it was seen in (null: none seen). */
    private class Listed(val row: ActivityRow, val block: Long?)

    private fun row(entry: HistoryEntry, names: Map<Address, String>): ActivityRow {
        val transfer = entry.transfer
        // Its other side: whom a transfer went to, the Safe a call was for, or, seen from that Safe,
        // the account that sent it.
        val (title, other, detail) = when (val action = transfer.action) {
            is SentAction.Transfer -> Triple("Sent ${amount(action.amount, action.asset)}", action.to, "To")
            // A zero approval takes the allowance back, before the whole amount is approved.
            is SentAction.TokenApproval -> if (action.amount.isZero) {
                Triple("Allowance reset for a swap", action.spender, "Router")
            } else {
                val approved = amount(action.amount, TransferAsset.Erc20Token(action.token))
                Triple("Approved $approved", action.spender, "Router")
            }
            is SentAction.Swap -> {
                val (symbol, decimals) = shown(action.buy)
                val title = "Swapped ${amount(action.amountIn, action.sell)} for at least " +
                    "${action.minimumOut.formatUnits(decimals)} $symbol"
                Triple(title, action.router, "On Uniswap ·")
            }
            is SentAction.SafeApproval -> safeCall("Approved", transfer.account, action.request)
            is SentAction.SafeExecution -> safeCall("Executed", transfer.account, action.request)
            is SentAction.SafeDeployment -> if (transfer.account == address) {
                Triple("Deployed a Safe", action.safe, "Safe")
            } else {
                Triple("Deployed", transfer.account, "Paid by")
            }
        }
        val short = shortAddress(Eip55.format(other))
        val named = names[other]?.let { "$it · $short" } ?: short
        val (status, state) = statusOf(entry)
        return ActivityRow(
            key = transfer.hash,
            opens = transfer.hash,
            title = title,
            detail = "$detail $named",
            about = Eip55.format(other),
            time = formatTime(transfer.sentAt),
            status = status,
            state = state,
        )
    }

    /** [units] of [asset] as screens say it: "0.25 ETH". */
    private fun amount(units: UInt256, asset: TransferAsset): String {
        val (symbol, decimals) = shown(asset)
        return "${units.formatUnits(decimals)} $symbol"
    }

    /** [asset]'s symbol as screens show it, and its decimals. */
    private fun shown(asset: TransferAsset): Pair<String, Int> = when (asset) {
        TransferAsset.Native -> network.nativeSymbol to NATIVE_DECIMALS
        is TransferAsset.Erc20Token -> displaySymbol(asset.token.symbol) to asset.token.decimals
    }

    /**
     * An approval or execution ([verb]) of [request]'s Safe transaction, sent by [sender]: its title
     * and other side as the account at [address] sees it, the Safe or the sender.
     */
    private fun safeCall(verb: String, sender: Address, request: SigningRequest): Triple<String, Address, String> =
        if (sender == address) {
            Triple("$verb a Safe transaction", request.safe, "Safe nonce ${request.nonce} ·")
        } else {
            Triple("$verb transaction ${request.nonce}", sender, "By")
        }

    private fun statusOf(entry: HistoryEntry): Pair<String, ActivityState> = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) {
            "Finalized in block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        is SentOutcome.Included -> if (outcome.succeeded) {
            "In block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        SentOutcome.NonceUsed ->
            "Mined while the app was not watching, unless another transaction took its nonce" to
                ActivityState.UNKNOWN
        SentOutcome.NotSeen -> if (entry.transfer.maybeSent) {
            "No peer confirmed its broadcast" to ActivityState.UNCONFIRMED
        } else {
            "No block seen to include it yet" to ActivityState.WAITING
        }
    }

    private companion object {
        const val NATIVE_DECIMALS = 18
        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
        const val ON_CHAIN = "On chain, from the Safe's verified logs"
        const val FAILED_ON_CHAIN = "Failed on chain: its call reverted"
        const val MODULE_WARNING = "A module can move this Safe's funds without its owners"
        const val HANDLER_WARNING = "Not the pinned fallback handler: it answers signature checks for this Safe"
    }
}
