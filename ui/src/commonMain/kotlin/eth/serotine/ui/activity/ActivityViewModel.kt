package eth.serotine.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.FALLBACK_HANDLER_REMOVED
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.GUARD_SET_WARNING
import eth.serotine.ui.components.MODULE_DISABLED_WARNING
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The Activity screen's ViewModel (docs/wallet-ux.md, screen 25; issue #85): the transactions this
 * device sent for the account at [address] ([ObserveAccountHistory]) and, for an account of the
 * wallet, what the engine's verified log index holds of it: every token transfer to or from it
 * ([ObserveTokenTransfers]) and, for a Safe, its own events ([ObserveSafeHistory]). The names the
 * wallet gives the addresses they concern come from [ObserveAccounts], the tokens' symbols and
 * decimals from the tokens it knows ([ObserveKnownTokens]). A log of a transaction listed as sent,
 * in a block, is left out when its row says the same. [formatTime] dates each in the user's own
 * time zone. No intents: a row opens its transaction's status, which is navigation.
 */
class ActivityViewModel(
    private val address: Address,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    private val accounts = observeAccounts()

    /** The account's kind while it is in the wallet; null while it is not. */
    private val kind = accounts
        .map { list -> (list as? AccountList.Loaded)?.accounts?.firstOrNull { it.address == address }?.kind }
        .distinctUntilChanged()

    /** The Safe's own events while the account is a Safe of the wallet; null for any other. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val safeHistory = kind.flatMapLatest {
        if (it == AccountKind.SAFE) observeSafeHistory(address) else flowOf(null)
    }

    /** The account's token transfers while it is in the wallet; null while it is not. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val transfers = kind.flatMapLatest { if (it != null) observeTokenTransfers(address) else flowOf(null) }

    /**
     * The tokens the wallet knows, by address, shown or not (a row says why one it does not show
     * is not named); null until the list is read, so that the rows already known never wait for
     * the tokens' checks.
     */
    private val tokens = observeKnownTokens()
        .map<KnownTokenList, Map<Address, KnownToken>?> { list -> list.tokens.associateBy { it.token.address } }
        .onStart { emit(null) }

    val uiState: StateFlow<ActivityUiState> =
        combine(observeAccountHistory(address), accounts, safeHistory, transfers, tokens, ::render).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            ActivityUiState.Loading(shortAddress(Eip55.format(address))),
        )

    private fun render(
        history: HistoryList,
        list: AccountList,
        safe: IndexedHistory<SafeChainEvent>?,
        transfers: IndexedHistory<TokenTransferEvent>?,
        tokens: Map<Address, KnownToken>?,
    ): ActivityUiState {
        val names = (list as? AccountList.Loaded)?.accounts.orEmpty().associate { it.address to it.label }
        val title = names[address] ?: shortAddress(Eip55.format(address))
        val entries = when (history) {
            HistoryList.Unreadable -> return ActivityUiState.Unavailable(title, UNREADABLE)
            is HistoryList.Loaded -> history.entries
        }
        val sent = entries.map {
            Listed(row(it, names), it.outcome.blockNumberOrNull, pending = it.outcome == SentOutcome.NotSeen)
        }
        if (transfers == null) return ActivityUiState.Shown(title, sent.map { it.row }, COVERAGE)
        // Only a row that names its block says what its logs say: one no block was seen for (a
        // transaction mined while the app was closed) gets the verified log beside it.
        val seenInBlocks = entries.filter { it.outcome.blockNumberOrNull != null }.associateBy { it.transfer.hash }
        val safeEvents = (safe as? IndexedHistory.Read)?.events.orEmpty()
            .filterNot { it.transactionHash in seenInBlocks && it.event.saidBySentRow() }
            .map { eventRow(it, names) to it }
        // Until the tokens are known, a transfer's amount cannot be said: none is listed yet.
        val tokenEvents = (transfers as? IndexedHistory.Read)?.events.orEmpty()
            .takeIf { tokens != null }.orEmpty()
            .filterNot { it.transfer.movesNothing() }
            .filterNot { event -> seenInBlocks[event.transactionHash]?.let { event.transfer.saidBy(it) } == true }
            .map { transferRow(it, names, tokens.orEmpty()) to it }
        val events = (safeEvents + tokenEvents)
            .sortedWith(compareByDescending<Pair<ActivityRow, IndexedEvent>> { it.second.blockNumber }
                .thenByDescending { it.second.logIndex })
            .map { (row, event) -> row to event.blockNumber }
        val deployedHere = entries.any { (it.transfer.action as? SentAction.SafeDeployment)?.safe == address }
        val since = if (deployedHere) "when it was deployed from this device" else "when it was added here"
        val parts = listOfNotNull(safe?.let { "its events" to it }, "its token transfers" to transfers)
        val covered = listOfNotNull(
            "Transactions sent from this device.",
            onChainCoverage(parts, since),
            if (safe == null) "ETH it received, and transactions sent from it elsewhere, are not listed yet." else null,
        )
        return ActivityUiState.Shown(
            title = title,
            rows = merged(sent, events),
            coverage = covered.joinToString(" "),
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
     * A fungible transfer of nothing: it moves no value, and anyone can have a token log one from
     * any address (`transferFrom` of zero needs no allowance), which is how a look-alike address is
     * planted in a history for the user to copy. Left out.
     */
    private fun TokenTransfer.movesNothing(): Boolean = this is TokenTransfer.Fungible && amount.isZero

    /**
     * Whether [entry]'s row, which names its block, says this transfer: the token, the amount and
     * the recipient the account sent in a transfer, or the token and the amount it sold in a swap.
     * What it received, and any other move of a token (a fee, a share to a third address), it does
     * not.
     */
    private fun TokenTransfer.saidBy(entry: HistoryEntry): Boolean {
        if (this !is TokenTransfer.Fungible || from != entry.transfer.account) return false
        val (asset, sent) = when (val action = entry.transfer.action) {
            is SentAction.Transfer -> if (action.to == to) action.asset to action.amount else return false
            is SentAction.Swap -> action.sell to action.amountIn
            else -> return false
        }
        return (asset as? TransferAsset.Erc20Token)?.token?.address == token && sent == amount
    }

    /**
     * The sent rows (newest first) and the [events] (newest first) in one list, newest block
     * first. A sent row still waiting for its block is newer than every block: it goes at the top.
     * One whose nonce was used with no block seen goes with the newest row sent before it that has
     * one (at the top when none has), and before the events of that row's block.
     */
    private fun merged(sent: List<Listed>, events: List<Pair<ActivityRow, Long>>): List<ActivityRow> {
        val sentBlocks = ArrayList<Long>(sent.size)
        for ((at, listed) in sent.withIndex()) {
            sentBlocks += listed.block
                ?: Long.MAX_VALUE.takeIf { listed.pending }
                ?: sent.drop(at + 1).firstNotNullOfOrNull { it.block }?.let { it + 1 }
                ?: Long.MAX_VALUE
        }
        val all = sent.mapIndexed { at, listed -> listed.row to sentBlocks[at] } + events
        // Stable: rows of one block keep the order they came in, the sent ones first.
        return all.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * What the list covers on chain: from which block on ([since] says why from there) each of the
     * [parts] ("its events" and its history) is listed, and how far each is checked; or why none is.
     */
    private fun onChainCoverage(parts: List<Pair<String, IndexedHistory<*>>>, since: String): String {
        val start = parts.firstNotNullOfOrNull { (_, history) ->
            (history as? IndexedHistory.Read)?.start ?: (history as? IndexedHistory.Checking)?.start
        }
        val all = parts.joinToString(" and ") { it.first }.replaceFirstChar { it.uppercase() }
        if (start == null) {
            return if (parts.all { it.second == IndexedHistory.NotFollowed }) {
                "$all on chain are listed once the light client is synced."
            } else {
                "$all on chain cannot be read right now."
            }
        }
        val said = parts.joinToString("; ") { (what, history) ->
            when (history) {
                is IndexedHistory.Read -> if (history.complete) {
                    what
                } else {
                    "$what, checked from block ${grouped(history.checked.first)} so far"
                }
                is IndexedHistory.Checking -> "$what, being checked"
                IndexedHistory.Unavailable -> "$what, which cannot be read right now"
                IndexedHistory.NotFollowed -> "$what, once the light client is synced"
            }
        }
        return "On chain from block ${grouped(start)} on, $since: $said."
    }

    /**
     * A token's [event] as a row: what moved, to or from whom, in which block. Only a token the
     * wallet shows has its transfer said as one: an unlisted contract can log any transfer it likes,
     * between any addresses, so its row says that the contract logged one, names the contract and
     * not the other side, and is marked as unconfirmed. A token the user added whose symbol is a
     * built-in token's says it is another contract, as every screen does.
     */
    private fun transferRow(
        event: TokenTransferEvent,
        names: Map<Address, String>,
        tokens: Map<Address, KnownToken>,
    ): ActivityRow {
        val transfer = event.transfer
        val contract = shortAddress(Eip55.format(transfer.token))
        val listed = tokens[transfer.token]
        val known = listed?.takeIf { it.shown }
        val direction = when {
            transfer.from == address && transfer.to == address -> Direction.ITSELF
            transfer.to == address -> Direction.IN
            else -> Direction.OUT
        }
        val row = ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = "",
            detail = "",
            about = Eip55.format(transfer.token),
            time = "Block ${grouped(event.blockNumber)}",
            status = TOKEN_ON_CHAIN,
            state = ActivityState.DONE,
            opens = null,
        )
        if (known == null) {
            val title = when (direction) {
                Direction.IN -> "An unlisted token logged a transfer to it"
                Direction.OUT -> "An unlisted token logged a transfer from it"
                Direction.ITSELF -> "An unlisted token logged a transfer to itself"
            }
            // What would name it, for the reason it is not named.
            val status = when {
                listed == null -> UNLISTED
                listed.check is BuiltInCheck.Disagrees -> CONTRADICTED
                else -> HIDDEN
            }
            return row.copy(
                title = title,
                detail = "Contract $contract",
                status = status,
                state = ActivityState.UNKNOWN,
            )
        }
        val symbol = displaySymbol(known.token.symbol)
        val what = when (transfer) {
            is TokenTransfer.Fungible -> "${transfer.amount.formatUnits(known.token.decimals)} $symbol"
            is TokenTransfer.Nft -> "$symbol NFT #${tokenId(transfer.tokenId)}"
        }
        val (title, other, detail) = when (direction) {
            Direction.ITSELF -> Triple("Moved $what to itself", transfer.token, "Token $contract")
            Direction.IN -> Triple("Received $what", transfer.from, "From ${named(transfer.from, names)}")
            Direction.OUT -> Triple("Sent $what", transfer.to, "To ${named(transfer.to, names)}")
        }
        return if (known.sharesBuiltInSymbol) {
            row.copy(
                title = title,
                detail = "$detail · token $contract",
                about = Eip55.format(other),
                status = "Not the built-in $symbol: another contract",
                state = ActivityState.WARNING,
            )
        } else {
            row.copy(title = title, detail = detail, about = Eip55.format(other))
        }
    }

    /** Which way a token moved, as the account sees it. */
    private enum class Direction { IN, OUT, ITSELF }

    /** An NFT's id as screens show it: whole when short, its ends when long. */
    private fun tokenId(id: UInt256): String =
        id.toDecimal().let { if (it.length > MAX_ID_DIGITS) it.take(6) + "…" + it.takeLast(4) else it }

    /** [other] as screens name it: the wallet's name for it and its short address, or the short address. */
    private fun named(other: Address, names: Map<Address, String>): String {
        val short = shortAddress(Eip55.format(other))
        return names[other]?.let { "$it · $short" } ?: short
    }

    /** A Safe's [event] as a row. */
    private fun eventRow(event: SafeChainEvent, names: Map<Address, String>): ActivityRow {
        fun named(other: Address): String = named(other, names)
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
        return ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = title,
            detail = detail,
            about = Eip55.format(other),
            time = "Block ${grouped(event.blockNumber)}",
            status = status,
            state = state,
            opens = null,
        )
    }

    /**
     * How an event's row is marked: failed for a call that reverted; a warning for what can move
     * the Safe's funds without its owners (CLAUDE.md, *Trust*): a module enabled, or a fallback
     * handler other than the pinned one; and for every other change that weakens the Safe, with the
     * warning its Safe transaction is signed with (docs/wallet-ux.md, 17): a module disabled, a
     * guard set or removed, a fallback handler removed.
     */
    private fun statusOf(event: SafeEvent): Pair<String, ActivityState> = when {
        (event as? SafeEvent.Executed)?.succeeded == false ||
            (event as? SafeEvent.ModuleExecuted)?.succeeded == false -> FAILED_ON_CHAIN to ActivityState.FAILED
        event is SafeEvent.ModuleEnabled -> MODULE_WARNING to ActivityState.WARNING
        event is SafeEvent.ModuleDisabled -> MODULE_DISABLED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged && event.guard == Address.ZERO -> GUARD_REMOVED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged -> GUARD_SET_WARNING to ActivityState.WARNING
        // Removing it is signed here, with a warning (owner ruling, 2026-10-20, #106): the row warns alike.
        event is SafeEvent.FallbackHandlerChanged && event.handler == Address.ZERO ->
            FALLBACK_HANDLER_REMOVED to ActivityState.WARNING
        event is SafeEvent.FallbackHandlerChanged && !PinnedFallbackHandlers.allowed(network, event.handler) ->
            HANDLER_WARNING to ActivityState.WARNING
        else -> ON_CHAIN to ActivityState.DONE
    }

    /** [count] owners, as a sentence says it: "1 owner", "2 owners". */
    private fun owners(count: Long): String = if (count == 1L) "1 owner" else "$count owners"

    /** A 32-byte hash as screens shorten it, as they do an address: "0xab12…cd34". */
    private fun shortHash(hash: String): String = shortAddress(hash)

    /**
     * A sent transaction's [row], the [block] it was seen in (null: none seen), and whether it is
     * [pending]: no block seen yet, nor its nonce used.
     */
    private class Listed(val row: ActivityRow, val block: Long?, val pending: Boolean)

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
        val (status, state) = statusOf(entry)
        return ActivityRow(
            key = transfer.hash,
            opens = transfer.hash,
            title = title,
            detail = "$detail ${named(other, names)}",
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
        const val MAX_ID_DIGITS = 12
        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
        const val ON_CHAIN = "On chain, from the Safe's verified logs"
        const val TOKEN_ON_CHAIN = "On chain, from the token's verified logs"
        const val UNLISTED = "The claim of a contract the wallet does not list: add it in Settings to see what moved"
        const val HIDDEN = "The claim of a token you hid: show it again in Settings → Tokens to see what moved"
        const val CONTRADICTED = "The claim of a built-in token whose contract says otherwise: what moved is not shown"
        const val FAILED_ON_CHAIN = "Failed on chain: its call reverted"
        const val MODULE_WARNING = "A module can move this Safe's funds without its owners"
        const val HANDLER_WARNING = "Not the pinned fallback handler: it answers signature checks for this Safe"
    }
}
