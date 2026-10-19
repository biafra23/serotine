package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The history of sent transactions over fake ports: [HistoryRecordingSentTransfers],
 * [FollowSentTransactions], [ObserveAccountHistory] and [ObserveSentTransfer]'s use of the history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionHistoriesTest {

    private val world = SafeWorld()
    private val history = MemoryTransactionHistory()
    private val bob = Address.of("0x" + "22".repeat(20))
    private var now = 1_800_000_100L

    private fun transfer(nonce: Long, sentAt: Long = 1_800_000_000, raw: Byte = nonce.toByte()) = SentTransfer(
        account = world.alice.address,
        nonce = nonce,
        signed = SignedTransfer(byteArrayOf(2, raw)),
        action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.ONE),
        maxCost = UInt256.of(1000),
        sentAt = sentAt,
    )

    /** Each hash's receipt as a test sets it, and how many watches of it are running. */
    private val progress = mutableMapOf<String, MutableStateFlow<TransactionProgress>>()
    private val watching = mutableMapOf<String, Int>()
    private val receipts = object : ReceiptTracker {
        override fun observe(hash: String): Flow<TransactionProgress> =
            progress.getOrPut(hash) { MutableStateFlow(TransactionProgress.Pending) }
                .onStart { watching[hash] = (watching[hash] ?: 0) + 1 }
                .onCompletion { watching[hash] = watching.getValue(hash) - 1 }
    }

    private fun receipt(transfer: SentTransfer) =
        progress.getOrPut(transfer.hash) { MutableStateFlow(TransactionProgress.Pending) }

    private fun TestScope.follow() {
        val follower = FollowSentTransactions(history, world.sent, receipts, world.inputs, clock = { now })
        backgroundScope.launch { follower() }
        runCurrent()
    }

    @Test
    fun aRecordGoesIntoTheHistoryTooAndAPruneLeavesTheHistoryAlone() = runTest {
        val sent = HistoryRecordingSentTransfers(world.sent, history)
        val first = transfer(7)

        assertEquals(true, sent.record(first))
        sent.prune(world.alice.address, minedCount = 8, unlistedBefore = 0)

        assertEquals(emptyList(), world.sent.transfers.value, "the nonce guard forgets it")
        assertEquals(listOf(HistoryEntry(first)), history.entries.value, "the history keeps it")
    }

    @Test
    fun aRecordAgainKeepsTheOutcomeTheHistoryHolds() = runTest {
        val sent = HistoryRecordingSentTransfers(world.sent, history)
        val first = transfer(7).copy(maybeSent = true)
        sent.record(first)
        history.settle(first.hash, SentOutcome.Included(120, succeeded = true))

        val retried = first.copy(maybeSent = false, sentAt = 1_800_000_050) // a retry a peer took
        sent.record(retried)

        assertEquals(listOf(HistoryEntry(retried, SentOutcome.Included(120, true))), history.entries.value)
    }

    @Test
    fun theFollowerAddsWhatTheRecordHoldsAndTheHistoryLacks() = runTest {
        val old = transfer(5)
        val known = transfer(6)
        world.sent.transfers.value = listOf(old, known) // recorded before the history existed
        history.record(known)

        follow()

        assertEquals(listOf(known.hash, old.hash), history.entries.value.map { it.transfer.hash })
    }

    @Test
    fun aReceiptSeenIsRecordedAndOnceFinalizedNoLongerWatched() = runTest {
        val sent = transfer(7)
        history.record(sent)
        follow()
        assertEquals(1, watching[sent.hash])

        receipt(sent).value = TransactionProgress.Included(120, succeeded = true)
        runCurrent()
        assertEquals(SentOutcome.Included(120, succeeded = true), history.outcomeOf(sent.hash))

        receipt(sent).value = TransactionProgress.Finalized(120, succeeded = true)
        runCurrent()
        assertEquals(SentOutcome.Finalized(120, succeeded = true), history.outcomeOf(sent.hash))
        assertEquals(0, watching[sent.hash], "ended for good")
    }

    @Test
    fun aReceiptNoLongerFoundLeavesTheBlockSeen() = runTest {
        val sent = transfer(7)
        history.record(sent)
        history.settle(sent.hash, SentOutcome.Included(120, succeeded = false))

        follow() // after a restart: the engine no longer looks back far enough to find it

        assertEquals(SentOutcome.Included(120, succeeded = false), history.outcomeOf(sent.hash))
        assertEquals(null, watching[sent.hash], "not watched again: it would never be found")
    }

    @Test
    fun aNonceUsedTwiceInARowWithNoReceiptReadsAsNonceUsed() = runTest {
        val sent = transfer(7)
        history.record(sent)
        world.inputs.mined = 8
        follow()
        assertEquals(SentOutcome.NotSeen, history.outcomeOf(sent.hash), "one read: its receipt may still come")

        advanceTimeBy(5.minutes)
        runCurrent()

        assertEquals(SentOutcome.NonceUsed, history.outcomeOf(sent.hash))
        assertEquals(0, watching[sent.hash], "ended for good")
    }

    @Test
    fun aReceiptSeenAfterTheNonceWasFoundUsedWins() = runTest {
        val sent = transfer(7)
        history.record(sent)
        world.inputs.mined = 8
        follow()

        receipt(sent).value = TransactionProgress.Included(130, succeeded = true)
        runCurrent()
        advanceTimeBy(5.minutes)
        runCurrent()

        assertEquals(SentOutcome.Included(130, succeeded = true), history.outcomeOf(sent.hash))
    }

    @Test
    fun anUnusedNonceKeepsItNotSeen() = runTest {
        val sent = transfer(7)
        history.record(sent)
        world.inputs.mined = 7
        follow()

        advanceTimeBy(30.minutes)
        runCurrent()

        assertEquals(SentOutcome.NotSeen, history.outcomeOf(sent.hash))
    }

    @Test
    fun aReceiptIsWatchedForADayAfterAPeerTookItAndNoLonger() = runTest {
        val old = transfer(7, sentAt = now - FOLLOW_SECONDS - 1)
        val recent = transfer(8, sentAt = now - 60)
        history.record(old)
        history.record(recent)
        follow()

        assertEquals(null, watching[old.hash], "past its follow time: the nonce alone tells")
        assertEquals(1, watching[recent.hash])
    }

    @Test
    fun anAccountsHistoryIsWhatItSentAndForASafeWhatWasSentForItNewestFirst() = runTest {
        val payment = SafeTx.call(Address.of("0x" + "bb".repeat(20)), UInt256.of(5), ByteArray(0), nonce = 4)
        val transferred = transfer(5, sentAt = 1_800_000_000)
        val executed = transfer(6, sentAt = 1_800_000_200)
            .copy(action = SentAction.SafeExecution(world.request(payment)))
        val deployed = transfer(7, sentAt = 1_800_000_100)
            .copy(action = SentAction.SafeDeployment(world.safe, listOf(world.alice.address), 1, "1.4.1", "Vault"))
        val elsewhere = transfer(1).copy(account = bob)
        listOf(transferred, executed, deployed, elsewhere).forEach { history.record(it) }

        val alice = ObserveAccountHistory(history)(world.alice.address).first() as HistoryList.Loaded
        val safe = ObserveAccountHistory(history)(world.safe).first() as HistoryList.Loaded

        assertEquals(listOf(executed, deployed, transferred), alice.entries.map { it.transfer })
        assertEquals(listOf(executed, deployed), safe.entries.map { it.transfer })
        history.readable.value = false
        assertEquals(HistoryList.Unreadable, ObserveAccountHistory(history)(world.safe).first())
    }

    @Test
    fun theStatusOfATransactionTheRecordForgotComesFromTheHistory() = runTest {
        val sent = transfer(7)
        history.record(sent)
        history.settle(sent.hash, SentOutcome.Included(120, succeeded = true))
        val observe = ObserveSentTransfer(world.sent, receipts, history)

        assertEquals(
            SentTransferStatus(
                sent,
                TransactionProgress.Included(120, succeeded = true),
                outcome = SentOutcome.Included(120, succeeded = true),
            ),
            observe(sent.hash).first(),
            "no receipt found any more: the block the history saw",
        )

        history.settle(sent.hash, SentOutcome.Finalized(120, succeeded = true))
        watching.clear()
        assertEquals(TransactionProgress.Finalized(120, succeeded = true), observe(sent.hash).first().progress)
        assertEquals(null, watching[sent.hash], "a finalized one is not looked up again")

        history.settle(sent.hash, SentOutcome.Included(120, succeeded = true))
        receipt(sent).value = TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            TransactionProgress.Included(120, succeeded = true),
            observe(sent.hash).first().progress,
            "not verifiable right now: the block the history saw",
        )

        receipt(sent).value = TransactionProgress.Pending
        history.settle(sent.hash, SentOutcome.NonceUsed)
        val used = observe(sent.hash).first()
        assertEquals(TransactionProgress.Pending, used.progress)
        assertEquals(SentOutcome.NonceUsed, used.outcome)
    }
}
