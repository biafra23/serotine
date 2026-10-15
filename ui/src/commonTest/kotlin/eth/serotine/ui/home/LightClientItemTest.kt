package eth.serotine.ui.home

import eth.serotine.domain.repository.LightClientStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class LightClientItemTest {

    @Test
    fun eachPhaseOfAColdStartHasATextAndAProgress() {
        assertEquals(
            LightClientItem("Starting the light client…", null, true),
            LightClientItem.from(LightClientStatus.Starting),
        )
        assertEquals(
            LightClientItem("Bootstrapping from the trusted checkpoint · 1 peer", null, true),
            LightClientItem.from(LightClientStatus.Bootstrapping(peers = 1)),
        )
        assertEquals(
            LightClientItem("Verifying sync committees · 3 of 4 periods · 2 peers", 0.75f, true),
            LightClientItem.from(LightClientStatus.Syncing(done = 3, total = 4, peers = 2)),
        )
        assertEquals(
            LightClientItem("Collecting verified state roots · 16 of 64", 0.25f, true),
            LightClientItem.from(LightClientStatus.CatchingUp(known = 16, needed = 64)),
        )
        assertEquals(
            LightClientItem("Verified at block 11,922,744", null, false),
            LightClientItem.from(LightClientStatus.Synced(headBlock = 11_922_744)),
        )
    }

    @Test
    fun anUnknownDistanceIsAnOpenEndedWaitNotZeroProgress() {
        assertEquals(
            LightClientItem("Verifying sync committees · 0 peers", null, true),
            LightClientItem.from(LightClientStatus.Syncing(done = 0, total = 0, peers = 0)),
        )
        assertEquals(
            LightClientItem("Collecting verified state roots", null, true),
            LightClientItem.from(LightClientStatus.CatchingUp(known = 0, needed = 0)),
        )
    }

    @Test
    fun refusalsAndAbsenceAreSaidPlainly() {
        assertEquals(
            LightClientItem("Sync refused: the trust anchor is 20 periods old, past the limit of 13", null, false),
            LightClientItem.from(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13)),
        )
        assertEquals(
            LightClientItem("No light client on this device", null, false),
            LightClientItem.from(LightClientStatus.Unavailable),
        )
    }
}
