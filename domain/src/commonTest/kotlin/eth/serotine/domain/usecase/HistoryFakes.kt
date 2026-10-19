package eth.serotine.domain.usecase

import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionHistory
import eth.serotine.domain.repository.yieldsTo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** A [TransactionHistory] in memory, as the port's contract says one behaves; [readable] false reads as unreadable. */
internal class MemoryTransactionHistory : TransactionHistory {
    val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val readable = MutableStateFlow(true)

    override fun observe(): Flow<HistoryList> =
        combine(entries, readable) { list, ok ->
            if (ok) HistoryList.Loaded(list) else HistoryList.Unreadable
        }

    override suspend fun record(transfer: SentTransfer): Boolean {
        val outcome = entries.value.lastOrNull { it.transfer.hash == transfer.hash }?.outcome ?: SentOutcome.NotSeen
        entries.value = entries.value.filterNot { it.transfer.hash == transfer.hash } + HistoryEntry(transfer, outcome)
        return true
    }

    override suspend fun settle(hash: String, outcome: SentOutcome): Boolean {
        val current = entries.value.lastOrNull { it.transfer.hash == hash }?.outcome ?: return false
        if (!current.yieldsTo(outcome)) return false
        entries.value = entries.value.map { if (it.transfer.hash == hash) it.copy(outcome = outcome) else it }
        return true
    }

    /** How the history says [hash] ended. */
    fun outcomeOf(hash: String): SentOutcome? = entries.value.lastOrNull { it.transfer.hash == hash }?.outcome
}
