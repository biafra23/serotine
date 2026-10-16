package eth.serotine.data.engine

import eth.serotine.data.engine.EngineSnapshot.Phase
import eth.serotine.domain.repository.LightClientStatus
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
class EngineLightClientTest {

    private val chain = FakeChain()

    private fun TestScope.client() = EngineLightClient(chain, StandardTestDispatcher(testScheduler), POLL)

    @Test
    fun followsAColdStartTheWayMyotisReadsIt() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        val collecting = launch { client().observeStatus().collect { seen += it } }
        fun step(snapshot: EngineSnapshot) {
            chain.snapshot = snapshot
            advanceTimeBy(POLL)
            runCurrent()
        }
        runCurrent()
        step(running(phase = Phase.STARTING))
        step(running(phase = Phase.SYNCING, hunting = true))
        step(running(phase = Phase.CATCHING_UP, start = 1450, current = 1451, target = 1454))
        step(running(phase = Phase.SYNCED, headAgeMs = Long.MAX_VALUE))
        step(running(phase = Phase.SYNCED, proofPeers = 0, head = 11_922_744))
        step(running(phase = Phase.SYNCED, head = 11_922_744, headTimestamp = 1_792_100_000))

        assertEquals(
            listOf(
                LightClientStatus.Starting,
                LightClientStatus.Bootstrapping(lookingForServers = true),
                // From the engine's own start period, not from whenever this subscriber came in.
                LightClientStatus.CatchingUp(done = 1, total = 4),
                LightClientStatus.WaitingForHead(headAgeSeconds = null),
                LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744, headTimestamp = null),
                LightClientStatus.Synced(headBlock = 11_922_744, headTimestamp = 1_792_100_000),
            ),
            seen,
        )
        collecting.cancel()
    }

    @Test
    fun aStalledHeadSleepAndAStaleAnchorAreStatesToo() = runTest {
        chain.snapshot = running(headAgeMs = 150_000)
        assertEquals(LightClientStatus.WaitingForHead(headAgeSeconds = 150), client().observeStatus().first())

        chain.snapshot = running(paused = true)
        assertEquals(LightClientStatus.Sleeping, client().observeStatus().first())

        chain.snapshot = running(phase = Phase.STALE_ANCHOR, current = 1400, target = 1420)
        assertEquals(
            LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13),
            client().observeStatus().first(),
        )

        chain.snapshot = running(phase = Phase.CATCHING_UP, start = -1, current = 5, target = 9)
        assertEquals(LightClientStatus.CatchingUp(done = 0, total = 0), client().observeStatus().first())
    }

    @Test
    fun aDownEngineOrNoneIsSaidOnceAndNotPolled() = runTest {
        chain.snapshot = EngineSnapshot.Down
        assertEquals(listOf<LightClientStatus>(LightClientStatus.Unavailable), client().observeStatus().toList())
        assertEquals(
            listOf<LightClientStatus>(LightClientStatus.Unavailable),
            EngineLightClient(null, StandardTestDispatcher(testScheduler), POLL).observeStatus().toList(),
        )
    }

    @Test
    fun aFailedPollIsSkippedNotReported() = runTest {
        val seen = mutableListOf<LightClientStatus>()
        chain.failure = IllegalStateException("engine busy")
        val collecting = launch { client().observeStatus().collect { seen += it } }
        runCurrent()

        chain.failure = null
        chain.snapshot = running(head = 5)
        advanceTimeBy(POLL)
        runCurrent()

        assertEquals(listOf<LightClientStatus>(LightClientStatus.Synced(5, null)), seen)
        collecting.cancel()
    }

    private companion object {
        val POLL = 1.seconds
    }
}
