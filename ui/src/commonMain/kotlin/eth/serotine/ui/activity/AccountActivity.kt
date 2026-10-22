package eth.serotine.ui.activity

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.ObserveAccountCowOrders
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * What an account's Activity shows, its detail's Activity tab (docs/wallet-ux.md, screen 25;
 * issue #85), for the account detail's ViewModel to read while the tab is shown: the transactions
 * this device sent for the account ([ObserveAccountHistory]) and, for an account of the wallet,
 * what the engine's verified log index holds of it: every token transfer to or from it
 * ([ObserveTokenTransfers]) and, for a Safe, its own events ([ObserveSafeHistory]). The names
 * the wallet gives the addresses they concern come from [ObserveAccounts], the tokens' symbols
 * and decimals from the tokens it knows ([ObserveKnownTokens]); a transfer received from an
 * address the wallet never sent to ([ObserveSentToAddresses]) and that is none of its accounts
 * is cautioned. A log of a transaction listed as sent, in a block, is left out when its row says
 * the same. The CoW orders the account signed here ([ObserveAccountCowOrders]) are listed among
 * the transactions, by when each was signed, and open their status. [formatTime] dates each in
 * the user's own time zone. A row opens its detail, which
 * is navigation.
 */
class AccountActivity(
    private val observeAccountHistory: ObserveAccountHistory,
    private val observeAccounts: ObserveAccounts,
    private val observeSafeHistory: ObserveSafeHistory,
    private val observeTokenTransfers: ObserveTokenTransfers,
    private val observeKnownTokens: ObserveKnownTokens,
    private val observeSentTo: ObserveSentToAddresses,
    private val observeCowOrders: ObserveAccountCowOrders,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) {

    /** The Activity of the account at [address], as it changes; nothing is read until it is collected. */
    fun of(address: Address): Flow<ActivityUiState> = Listing(address).state

    /** One account's Activity: the [address] its rows and sources are of. */
    private inner class Listing(private val address: Address) {

        private val rows = ActivityRows(address, network, formatTime)

        private val sources = ActivitySources(
            address,
            observeAccounts,
            observeSafeHistory,
            observeTokenTransfers,
            observeKnownTokens,
            observeSentTo,
        )

        val state: Flow<ActivityUiState> = combine(
            combine(observeAccountHistory(address), observeCowOrders(address)) { history, orders -> history to orders },
            sources.parties,
            sources.safeHistory,
            sources.transfers,
            sources.tokens,
            ::render,
        )

        private fun render(
            sentHere: Pair<HistoryList, List<CowOrderRecord>>,
            parties: Parties,
            safe: IndexedHistory<SafeChainEvent>?,
            transfers: IndexedHistory<TokenTransferEvent>?,
            tokens: Map<Address, KnownToken>?,
        ): ActivityUiState {
            val names = parties.names
            val (history, orders) = sentHere
            val entries = when (history) {
                HistoryList.Unreadable -> return ActivityUiState.Unavailable(UNREADABLE)
                is HistoryList.Loaded -> history.entries
            }
            // The CoW orders signed here among them, by when each was signed: a live one waits for
            // its end as a transaction waits for its block, a filled or cancelled one goes by the
            // block of its fill or its cancellation, and one never filled by where it was signed
            // (the block that proved it came later).
            val sent = (
                entries.map {
                    val listed = Listed(
                        rows.row(it, names),
                        it.outcome.blockNumberOrNull,
                        pending = it.outcome == SentOutcome.NotSeen,
                    )
                    it.transfer.sentAt to listed
                } + orders.map { it.signedAt to Listed(rows.orderRow(it), it.end?.placedAt, pending = it.live) }
            ).sortedByDescending { it.first }.map { it.second }
            if (transfers == null) return ActivityUiState.Shown(sent.map { it.row }, COVERAGE)
            // Only a row that names its block says what its logs say: one no block was seen for (a
            // transaction mined while the app was closed) gets the verified log beside it.
            val seenInBlocks = entries.filter { it.outcome.blockNumberOrNull != null }.associateBy { it.transfer.hash }
            // A CoW order's fill says what it sold: the sale's own transfer, in the fill, is not listed again.
            val fills = orders.mapNotNull { order ->
                (order.end as? CowOrderEnd.Filled)?.let { it.transactionHash.lowercase() to order }
            }.toMap()
            val safeEvents = (safe as? IndexedHistory.Read)?.events.orEmpty()
                .filterNot { it.transactionHash in seenInBlocks && it.event.saidBySentRow() }
                .map { rows.eventRow(it, parties) to it }
            // Until the tokens are known, a transfer's amount cannot be said: none is listed yet.
            val tokenEvents = (transfers as? IndexedHistory.Read)?.events.orEmpty()
                .takeIf { tokens != null }.orEmpty()
                .filterNot { it.transfer.movesNothing() }
                .filterNot { event -> seenInBlocks[event.transactionHash]?.let { event.transfer.saidBy(it) } == true }
                .filterNot { event ->
                    fills[event.transactionHash.lowercase()]?.let { event.transfer.soldIn(it) } == true
                }
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
                if (safe == null) NOT_LISTED_YET else null,
            )
            return ActivityUiState.Shown(
                rows = merged(sent, events),
                coverage = covered.joinToString(" "),
                empty = "Nothing listed yet.",
            )
        }
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
     * One with no block (a nonce used with no block seen, a CoW order never filled) goes with the
     * newest row sent before it that has one, before the events of that row's block; when none
     * has, just below the newest row sent after it that has one, and at the top when none has.
     */
    private fun merged(sent: List<Listed>, events: List<Pair<ActivityRow, Long>>): List<ActivityRow> {
        val sentBlocks = ArrayList<Long>(sent.size)
        for ((at, listed) in sent.withIndex()) {
            sentBlocks += listed.block
                ?: Long.MAX_VALUE.takeIf { listed.pending }
                ?: sent.drop(at + 1).firstNotNullOfOrNull { it.block }?.let { it + 1 }
                // None sent before it has a block: just below the newest sent after it that has one.
                ?: sentBlocks.lastOrNull { it != Long.MAX_VALUE }
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

    /**
     * The block an ended order's row goes by: its fill's, or its cancellation's. One never filled has
     * none: the block that proved it is the finalized one when the proof ran, which can be days after
     * it was signed, so it goes where it was signed, as a transaction without a block does.
     */
    private val CowOrderEnd.placedAt: Long?
        get() = when (this) {
            is CowOrderEnd.Filled -> block
            is CowOrderEnd.Unfilled -> endBlock.takeIf { cancelled }
            CowOrderEnd.NotProvable -> null
        }

    /** Whether this transfer is what the filled [order] sold: of its token, from its account, its whole sale. */
    private fun TokenTransfer.soldIn(order: CowOrderRecord): Boolean =
        this is TokenTransfer.Fungible && from == order.account && token == order.order.sellToken &&
            amount == order.order.sellAmount

    private companion object {
        const val NOT_LISTED_YET = "ETH it received, and transactions sent from it elsewhere, are not listed yet."
        const val COVERAGE = "Transactions sent from this device. Transactions received, and those sent " +
            "from this account elsewhere, are not listed yet."
        const val UNREADABLE = "The history of your transactions cannot be read right now. Serotine keeps trying."
    }
}
