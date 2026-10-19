package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedNativeBalancesTest {

    private val address = Address.of("0x" + "ab".repeat(20))
    private val chain = FakeChain()

    private fun TestScope.balances() = VerifiedNativeBalances(chain, StandardTestDispatcher(testScheduler), POLL)

    private fun TestScope.step(snapshot: EngineSnapshot) {
        chain.snapshot = snapshot
        advanceTimeBy(POLL)
        runCurrent()
    }

    @Test
    fun aBalanceIsLabelledWithTheBlockItsProofAnchorsTo() = runTest {
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances().observeBalance(address).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<BalanceReading>(BalanceReading.Syncing), seen)

        // The head moved on to 101 while the proof was fetched; the proof says 101, and so
        // does the reading.
        chain.proofs[address] = ProvenBalance("1500000000000000000", blockNumber = 101)
        step(running(head = 100))

        assertEquals(BalanceReading.Verified(Wei.of("1500000000000000000"), blockNumber = 101), seen.last())
        collecting.cancel()
    }

    @Test
    fun provesAgainOnlyWhenTheHeadMoves() = runTest {
        chain.snapshot = running(head = 7)
        chain.proofs[address] = ProvenBalance("1", 7)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances().observeBalance(address).collect { seen += it } }
        runCurrent()
        repeat(3) { step(running(head = 7)) }
        assertEquals(1, chain.proofRequests.size, "same head, no new proof")

        chain.proofs[address] = ProvenBalance("2", 8)
        step(running(head = 8))

        assertEquals(2, chain.proofRequests.size)
        assertEquals(
            listOf<BalanceReading>(BalanceReading.Verified(Wei.of("1"), 7), BalanceReading.Verified(Wei.of("2"), 8)),
            seen,
        )
        collecting.cancel()
    }

    @Test
    fun aSecondSubscriberOnTheSameHeadReusesTheProof() = runTest {
        chain.snapshot = running(head = 9)
        chain.proofs[address] = ProvenBalance("5", 9)
        val balances = balances()

        assertEquals(BalanceReading.Verified(Wei.of("5"), 9), balances.observeBalance(address).first())
        assertEquals(BalanceReading.Verified(Wei.of("5"), 9), balances.observeBalance(address).first())
        assertEquals(1, chain.proofRequests.size)
    }

    @Test
    fun noAmountIsShownWhileReadsCannotBeVerified() = runTest {
        chain.proofs[address] = ProvenBalance("5", 9)
        val balances = balances()
        val unverifiable = listOf(
            running(phase = Phase.CATCHING_UP),
            running(paused = true),
            running(headAgeMs = 60_000), // a stalled head
            running(headAgeMs = Long.MAX_VALUE), // no verified head yet
            running(proofPeers = 0), // nobody serves state proofs
        )

        for (snapshot in unverifiable) {
            chain.snapshot = snapshot
            assertEquals(BalanceReading.Syncing, balances.observeBalance(address).first(), "$snapshot")
        }
        assertEquals(emptyList(), chain.proofRequests)
    }

    @Test
    fun theLastProofIsShownAsLastKnownWhileReadsCannotBeVerified() = runTest {
        chain.proofs[address] = ProvenBalance("5", 9)
        val seen = mutableListOf<BalanceReading>()
        chain.snapshot = running(head = 9, headTimestamp = 1_792_100_000)
        val collecting = launch { balances().observeBalance(address).collect { seen += it } }
        runCurrent()
        assertEquals(BalanceReading.Verified(Wei.of("5"), 9), seen.last())

        // The head goes stale: the amount stays, labelled with its block and that head's time.
        step(running(head = 9, headTimestamp = 1_792_100_000, headAgeMs = 60_000))
        val lastKnown = BalanceReading.LastKnown(Wei.of("5"), blockNumber = 9, asOf = 1_792_100_000)
        assertEquals(lastKnown, seen.last())
        for (snapshot in listOf(running(paused = true), running(phase = Phase.CATCHING_UP), EngineSnapshot.Booting)) {
            step(snapshot)
            assertEquals(lastKnown, seen.last(), "$snapshot")
        }
        assertEquals(1, chain.proofRequests.size, "nothing proven without a fresh head")

        // A stale anchor is a refusal, not a head to wait for: no amount from before.
        step(running(phase = Phase.STALE_ANCHOR))
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.STALE_ANCHOR), seen.last())

        chain.proofs[address] = ProvenBalance("6", 12)
        step(running(head = 12))
        assertEquals(BalanceReading.Verified(Wei.of("6"), 12), seen.last(), "verified again at a fresh head")
        collecting.cancel()
    }

    @Test
    fun aFailedProofShowsNoAmountFromBefore() = runTest {
        chain.snapshot = running(head = 9)
        chain.proofs[address] = ProvenBalance("5", 9)
        val balances = balances()
        assertEquals(BalanceReading.Verified(Wei.of("5"), 9), balances.observeBalance(address).first())

        chain.proofs.remove(address)
        chain.snapshot = running(head = 10)
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.UNPROVEN), balances.observeBalance(address).first())
    }

    @Test
    fun aStaleAnchorAndAFailedProofAreSaidSo() = runTest {
        val balances = balances()

        chain.snapshot = running(phase = Phase.STALE_ANCHOR)
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.STALE_ANCHOR), balances.observeBalance(address).first())

        chain.snapshot = running(head = 3) // no proof for the address: the engine could not prove it
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.UNPROVEN), balances.observeBalance(address).first())

        chain.proofs[address] = ProvenBalance("not a number", 3)
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.UNPROVEN), balances.observeBalance(address).first())
    }

    @Test
    fun anEngineStateThatFailsToReadIsNotVerifiedAndThePollGoesOn() = runTest {
        chain.snapshot = running(head = 5)
        chain.proofs[address] = ProvenBalance("2", 5)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances().observeBalance(address).collect { seen += it } }
        runCurrent()
        assertEquals(BalanceReading.Verified(Wei.of("2"), 5), seen.last())

        // The engine's state crosses JNA: a broken native library fails with an Error.
        chain.failure = Error("native library broken")
        step(running(head = 6))
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.UNPROVEN), seen.last(), "not the old amount")
        // Still failing: one poll per interval, not a busy loop.
        val before = chain.snapshots
        step(running(head = 6))
        assertEquals(before + 1, chain.snapshots)

        chain.failure = null
        chain.proofs[address] = ProvenBalance("3", 6)
        step(running(head = 6))
        assertEquals(BalanceReading.Verified(Wei.of("3"), 6), seen.last(), "still polling")
        collecting.cancel()
    }

    @Test
    fun aCancellationTheEngineThrowsIsAFailureNotTheEndOfThePoll() = runTest {
        chain.snapshot = running(head = 5)
        chain.proofs[address] = ProvenBalance("2", 5)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances().observeBalance(address).collect { seen += it } }
        runCurrent()

        chain.failure = CancellationException("thrown by the engine's own code")
        step(running(head = 6))
        assertEquals(BalanceReading.Unavailable(ReadUnavailable.UNPROVEN), seen.last())

        chain.failure = null
        chain.proofs[address] = ProvenBalance("3", 6)
        step(running(head = 6))
        assertEquals(BalanceReading.Verified(Wei.of("3"), 6), seen.last(), "the poll went on")
        collecting.cancel()
    }

    @Test
    fun aDownEngineOrNoneIsSaidOnceAndNotPolled() = runTest {
        chain.snapshot = EngineSnapshot.Down
        assertEquals(
            listOf<BalanceReading>(BalanceReading.Unavailable(ReadUnavailable.NO_ENGINE)),
            balances().observeBalance(address).toList(),
        )
        assertEquals(
            listOf<BalanceReading>(BalanceReading.Unavailable(ReadUnavailable.NO_ENGINE)),
            VerifiedNativeBalances(null, StandardTestDispatcher(testScheduler), POLL).observeBalance(address).toList(),
        )
    }

    private companion object {
        val POLL = 4.seconds
    }

    @Test
    fun onlyTheMostRecentlyProvenAddressesAreKept() = runTest {
        chain.snapshot = running(head = 7)
        val addresses = (0 until 70).map { Address.of("0x" + it.toString(16).padStart(40, '0')) }
        addresses.forEach { chain.proofs[it] = ProvenBalance("1", 7) }
        val balances = balances()
        addresses.forEach { balances.observeBalance(it).first() }
        chain.proofRequests.clear()

        balances.observeBalance(addresses.last()).first()
        balances.observeBalance(addresses.first()).first()

        assertEquals(listOf(addresses.first()), chain.proofRequests, "the oldest was let go, the newest kept")
    }
}
