package eth.serotine.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountHistory
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
 * time zone. No intents: a row opens its detail, which is navigation.
 */
class ActivityViewModel(
    private val address: Address,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    network: Network,
    formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    private val rows = ActivityRows(address, network, formatTime)

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
            Listed(rows.row(it, names), it.outcome.blockNumberOrNull, pending = it.outcome == SentOutcome.NotSeen)
        }
        if (transfers == null) return ActivityUiState.Shown(title, sent.map { it.row }, COVERAGE)
        // Only a row that names its block says what its logs say: one no block was seen for (a
        // transaction mined while the app was closed) gets the verified log beside it.
        val seenInBlocks = entries.filter { it.outcome.blockNumberOrNull != null }.associateBy { it.transfer.hash }
        val safeEvents = (safe as? IndexedHistory.Read)?.events.orEmpty()
            .filterNot { it.transactionHash in seenInBlocks && it.event.saidBySentRow() }
            .map { rows.eventRow(it, names) to it }
        // Until the tokens are known, a transfer's amount cannot be said: none is listed yet.
        val tokenEvents = (transfers as? IndexedHistory.Read)?.events.orEmpty()
            .takeIf { tokens != null }.orEmpty()
            .filterNot { it.transfer.movesNothing() }
            .filterNot { event -> seenInBlocks[event.transactionHash]?.let { event.transfer.saidBy(it) } == true }
            .map { rows.transferRow(it, names, tokens.orEmpty()) to it }
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
     * A sent transaction's [row], the [block] it was seen in (null: none seen), and whether it is
     * [pending]: no block seen yet, nor its nonce used.
     */
    private class Listed(val row: ActivityRow, val block: Long?, val pending: Boolean)

    private companion object {
        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
    }
}
