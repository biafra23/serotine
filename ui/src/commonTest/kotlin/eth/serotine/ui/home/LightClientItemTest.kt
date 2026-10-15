package eth.serotine.ui.home

import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class LightClientItemTest {

    @Test
    fun eachPhaseOfAColdStartHasATextAndAProgress() {
        assertEquals(
            LightClientItem("Starting the light client…", null, true),
            item(LightClientStatus.Starting),
        )
        assertEquals(
            LightClientItem(
                "Bootstrapping from the trusted checkpoint · looking for light-client servers",
                null,
                true,
            ),
            item(LightClientStatus.Bootstrapping(lookingForServers = true)),
        )
        assertEquals(
            LightClientItem("Verifying sync committees · 3 of 4 periods", 0.75f, true),
            item(LightClientStatus.CatchingUp(done = 3, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · waiting for the first verified head", null, true),
            item(LightClientStatus.WaitingForHead(headAgeSeconds = null)),
        )
        assertEquals(
            LightClientItem("Synced to block 11,922,744 · looking for peers that serve state proofs", null, true),
            item(LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744, headTimestamp = null)),
        )
        assertEquals(
            LightClientItem("Verified at block 11,922,744 · 8 s ago", null, false),
            item(LightClientStatus.Synced(headBlock = 11_922_744, headTimestamp = NOW - 8)),
        )
    }

    @Test
    fun theHeadIsDatedRelativeToNowWhenItsTimeIsKnown() {
        fun synced(timestamp: Long?) = LightClientItem.from(LightClientStatus.Synced(42, timestamp), NOW).text
        assertEquals("Verified at block 42", synced(null))
        assertEquals("Verified at block 42 · just now", synced(NOW))
        assertEquals("Verified at block 42 · 59 s ago", synced(NOW - 59))
        assertEquals("Verified at block 42 · 5 min ago", synced(NOW - 300))
        assertEquals("Verified at block 42 · 3 h ago", synced(NOW - 3 * 3_600))
        assertEquals(
            "Synced to block 42 · 12 s ago · looking for peers that serve state proofs",
            LightClientItem.from(LightClientStatus.WaitingForProofPeers(42, NOW - 12), NOW).text,
        )
    }

    private fun item(status: LightClientStatus) = LightClientItem.from(status, NOW)

    private companion object {
        const val NOW = 1_792_100_000L
    }

    @Test
    fun openEndedWaitsHaveNoFakeProgress() {
        assertEquals(
            LightClientItem("Verifying sync committees", null, true),
            item(LightClientStatus.CatchingUp(done = 0, total = 0)),
        )
        assertEquals(
            LightClientItem("Finishing sync", null, true),
            item(LightClientStatus.CatchingUp(done = 4, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · the verified head is 3 min old, waiting for a fresh one", null, true),
            item(LightClientStatus.WaitingForHead(headAgeSeconds = 185)),
        )
    }

    @Test
    fun sleepRefusalsAndAbsenceAreSaidPlainly() {
        assertEquals(
            LightClientItem("Light client asleep while the app is in the background", null, false),
            item(LightClientStatus.Sleeping),
        )
        assertEquals(
            LightClientItem("Sync refused: the trust anchor is 20 periods old, past the limit of 13", null, false),
            item(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13)),
        )
        assertEquals(
            LightClientItem("Sync refused: the trust anchor is 20 periods old", null, false),
            item(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 0)),
        )
        assertEquals(
            LightClientItem("No light client on this device", null, false),
            item(LightClientStatus.Unavailable),
        )
    }
}
