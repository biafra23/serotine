package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BalanceReading.Reason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun followsTheLightClientFromSyncingToAVerifiedBalance() = runTest {
        val balances = VerifiedNativeBalances(chain, StandardTestDispatcher(testScheduler), POLL)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances.observeBalance(address).collect { seen += it } }

        runCurrent()
        assertEquals(listOf<BalanceReading>(BalanceReading.Syncing), seen)

        chain.status = ChainStatus.Synced(headBlock = 100)
        chain.balances[100L] = "1500000000000000000"
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(BalanceReading.Verified(Wei.of("1500000000000000000"), blockNumber = 100), seen.last())
        assertEquals(listOf(address to 100L), chain.reads, "read at the verified head itself")
        collecting.cancel()
    }

    @Test
    fun readsAgainOnlyWhenTheHeadMoves() = runTest {
        chain.status = ChainStatus.Synced(headBlock = 7)
        chain.balances[7L] = "1"
        chain.balances[8L] = "2"
        val balances = VerifiedNativeBalances(chain, StandardTestDispatcher(testScheduler), POLL)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances.observeBalance(address).collect { seen += it } }

        runCurrent()
        advanceTimeBy(POLL * 3)
        runCurrent()
        assertEquals(1, chain.reads.size, "same head, no new read")

        chain.status = ChainStatus.Synced(headBlock = 8)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf(7L, 8L), chain.reads.map { it.second })
        assertEquals(
            listOf<BalanceReading>(
                BalanceReading.Verified(Wei.of("1"), 7),
                BalanceReading.Verified(Wei.of("2"), 8),
            ),
            seen,
            "equal readings are not repeated",
        )
        collecting.cancel()
    }

    @Test
    fun anUnprovableBalanceIsSaidSoAndRetried() = runTest {
        chain.status = ChainStatus.Synced(headBlock = 5)
        val balances = VerifiedNativeBalances(chain, StandardTestDispatcher(testScheduler), POLL)
        val seen = mutableListOf<BalanceReading>()
        val collecting = launch { balances.observeBalance(address).collect { seen += it } }

        runCurrent()
        assertEquals(BalanceReading.Unavailable(Reason.UNPROVEN), seen.last())

        chain.balances[5L] = "42"
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(BalanceReading.Verified(Wei.of("42"), 5), seen.last())
        collecting.cancel()
    }

    @Test
    fun theLightClientsRefusalsAreReadingsNotFailures() = runTest {
        val balances = VerifiedNativeBalances(chain, StandardTestDispatcher(testScheduler), POLL)

        chain.status = ChainStatus.StaleAnchor
        assertEquals(BalanceReading.Unavailable(Reason.STALE_ANCHOR), balances.observeBalance(address).first())

        chain.status = ChainStatus.Down
        assertEquals(BalanceReading.Unavailable(Reason.NO_ENGINE), balances.observeBalance(address).first())

        chain.status = ChainStatus.Synced(headBlock = 1)
        chain.failure = IllegalStateException("engine gone")
        assertEquals(BalanceReading.Unavailable(Reason.UNPROVEN), balances.observeBalance(address).first())

        chain.failure = null
        chain.balances[1L] = "not a number"
        assertEquals(BalanceReading.Unavailable(Reason.UNPROVEN), balances.observeBalance(address).first())
    }

    @Test
    fun aDeviceWithoutAnEngineSaysSoOnce() = runTest {
        val balances = VerifiedNativeBalances(null, StandardTestDispatcher(testScheduler), POLL)

        assertEquals(
            listOf<BalanceReading>(BalanceReading.Unavailable(Reason.NO_ENGINE)),
            balances.observeBalance(address).take(1).toList(),
        )
    }

    private class FakeChain : VerifiedChain {
        var status: ChainStatus = ChainStatus.Syncing
        val balances = mutableMapOf<Long, String>()
        val reads = mutableListOf<Pair<Address, Long>>()
        var failure: Exception? = null

        override fun status(): ChainStatus = status

        override fun beacon(): BeaconSnapshot = error("not used by these tests")

        override fun balanceWei(address: Address, blockNumber: Long): String? {
            failure?.let { throw it }
            reads += address to blockNumber
            return balances[blockNumber]
        }
    }

    private companion object {
        val POLL = 4.seconds
    }
}
