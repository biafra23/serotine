package eth.serotine.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Activity screen's ViewModel (docs/wallet-ux.md, screen 25; issue #85): the transactions this
 * device sent for the account at [address] ([ObserveAccountHistory]) and, for an account of the
 * wallet, what the engine's verified log index holds of it: every token transfer to or from it
 * ([ObserveTokenTransfers]) and, for a Safe, its own events ([ObserveSafeHistory]). The names the
 * wallet gives the addresses they concern come from [ObserveAccounts], the tokens' symbols and
 * decimals from the tokens it knows ([ObserveKnownTokens]); a transfer received from an address the
 * wallet never sent to ([ObserveSentToAddresses]) and that is none of its accounts is cautioned. A
 * log of a transaction listed as sent, in a block, is left out when its row says the same.
 * [formatTime] dates each in the user's own time zone. No intents: a row opens its detail, which is navigation.
 */
class ActivityViewModel(
    private val address: Address,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    observeSentTo: ObserveSentToAddresses,
    network: Network,
    formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    private val rows = ActivityRows(address, network, formatTime)

    private val sources = ActivitySources(
        address,
        observeAccounts,
        observeSafeHistory,
        observeTokenTransfers,
        observeKnownTokens,
        observeSentTo,
    )

    val uiState: StateFlow<ActivityUiState> =
        combine(
            observeAccountHistory(address),
            sources.parties,
            sources.safeHistory,
            sources.transfers,
            sources.tokens,
            ::render,
        ).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            ActivityUiState.Loading(sources.shortTitle),
        )

    private fun render(
        history: HistoryList,
        parties: Parties,
        safe: IndexedHistory<SafeChainEvent>?,
        transfers: IndexedHistory<TokenTransferEvent>?,
        tokens: Map<Address, KnownToken>?,
    ): ActivityUiState {
        val names = parties.names
        val title = sources.titleOf(names)
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
            .map { rows.eventRow(it, parties) to it }
        // Until the tokens are known, a transfer's amount cannot be said: none is listed yet.
        val tokenEvents = (transfers as? IndexedHistory.Read)?.events.orEmpty()
            .takeIf { tokens != null }.orEmpty()
            .filterNot { it.transfer.movesNothing() }
            .filterNot { event -> seenInBlocks[event.transactionHash]?.let { event.transfer.saidBy(it) } == true }
            .map { rows.transferRow(it, parties, tokens.orEmpty()) to it }
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
