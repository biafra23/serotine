package eth.serotine.data.engine

import eth.serotine.data.engine.BeaconSnapshot.Phase
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.LightClientStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLightClientTest {

    private val chain = FakeChain()

    private fun running(
        phase: Phase,
        bootstrapped: Boolean = true,
        current: Long = 0,
        target: Long = 0,
        roots: Int = 0,
        head: Long? = null,
    ) = BeaconSnapshot.Running(phase, bootstrapped, current, target, 2, roots, 64, head, 13)

    @Test
    fun followsAColdStartFromBootToSynced() = runTest {
        val client = EngineLightClient(chain, StandardTestDispatcher(testScheduler), POLL)
        val seen = mutableListOf<LightClientStatus>()
        val collecting = launch { client.observeStatus().collect { seen += it } }

        suspend fun step(snapshot: BeaconSnapshot) {
            chain.beacon = snapshot
            advanceTimeBy(POLL)
            runCurrent()
        }
        runCurrent()
        step(running(Phase.STARTING, bootstrapped = false))
        step(running(Phase.SYNCING, current = 1450, target = 1454))
        step(running(Phase.SYNCING, current = 1452, target = 1454))
        step(running(Phase.CATCHING_UP, current = 1454, target = 1454, roots = 16))
        step(running(Phase.SYNCED, current = 1454, target = 1454, roots = 64, head = 11_922_744))

        assertEquals(
            listOf(
                LightClientStatus.Starting,
                LightClientStatus.Bootstrapping(peers = 2),
                // Progress counts from the first period seen after the bootstrap.
                LightClientStatus.Syncing(done = 0, total = 4, peers = 2),
                LightClientStatus.Syncing(done = 2, total = 4, peers = 2),
                LightClientStatus.CatchingUp(known = 16, needed = 64),
                LightClientStatus.Synced(headBlock = 11_922_744),
            ),
            seen,
        )
        collecting.cancel()
    }

    @Test
    fun aStaleAnchorAndADeadEngineAreStatesToo() = runTest {
        val client = EngineLightClient(chain, StandardTestDispatcher(testScheduler), POLL)

        chain.beacon = running(Phase.STALE_ANCHOR, current = 1400, target = 1420)
        assertEquals(
            LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13),
            client.observeStatus().first(),
        )

        chain.beacon = BeaconSnapshot.Down
        assertEquals(LightClientStatus.Unavailable, client.observeStatus().first())

        assertEquals(
            LightClientStatus.Unavailable,
            EngineLightClient(null, StandardTestDispatcher(testScheduler), POLL).observeStatus().first(),
        )
    }

    @Test
    fun aFailedPollIsSkippedNotReported() = runTest {
        val client = EngineLightClient(chain, StandardTestDispatcher(testScheduler), POLL)
        val seen = mutableListOf<LightClientStatus>()
        chain.failure = IllegalStateException("engine busy")
        val collecting = launch { client.observeStatus().collect { seen += it } }
        runCurrent()

        chain.failure = null
        chain.beacon = running(Phase.SYNCED, head = 5)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf<LightClientStatus>(LightClientStatus.Synced(5)), seen)
        collecting.cancel()
    }

    private class FakeChain : VerifiedChain {
        var beacon: BeaconSnapshot = BeaconSnapshot.Booting
        var failure: Exception? = null

        override fun status(): ChainStatus = error("not used by these tests")

        override fun beacon(): BeaconSnapshot {
            failure?.let { throw it }
            return beacon
        }

        override fun balanceWei(address: Address, blockNumber: Long): String? = error("not used by these tests")
    }

    private companion object {
        val POLL = 1.seconds
    }
}
