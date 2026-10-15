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
            LightClientItem("Bootstrapping from the trusted checkpoint · looking for light-client servers", null, true),
            LightClientItem.from(LightClientStatus.Bootstrapping(lookingForServers = true)),
        )
        assertEquals(
            LightClientItem("Verifying sync committees · 3 of 4 periods", 0.75f, true),
            LightClientItem.from(LightClientStatus.CatchingUp(done = 3, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · waiting for the first verified head", null, true),
            LightClientItem.from(LightClientStatus.WaitingForHead(headAgeSeconds = null)),
        )
        assertEquals(
            LightClientItem("Synced to block 11,922,744 · looking for peers that serve state proofs", null, true),
            LightClientItem.from(LightClientStatus.WaitingForProofPeers(headBlock = 11_922_744)),
        )
        assertEquals(
            LightClientItem("Verified at block 11,922,744", null, false),
            LightClientItem.from(LightClientStatus.Synced(headBlock = 11_922_744)),
        )
    }

    @Test
    fun openEndedWaitsHaveNoFakeProgress() {
        assertEquals(
            LightClientItem("Verifying sync committees", null, true),
            LightClientItem.from(LightClientStatus.CatchingUp(done = 0, total = 0)),
        )
        assertEquals(
            LightClientItem("Finishing sync", null, true),
            LightClientItem.from(LightClientStatus.CatchingUp(done = 4, total = 4)),
        )
        assertEquals(
            LightClientItem("Synced · the verified head is 3 min old, waiting for a fresh one", null, true),
            LightClientItem.from(LightClientStatus.WaitingForHead(headAgeSeconds = 185)),
        )
    }

    @Test
    fun sleepRefusalsAndAbsenceAreSaidPlainly() {
        assertEquals(
            LightClientItem("Light client asleep while the app is in the background", null, false),
            LightClientItem.from(LightClientStatus.Sleeping),
        )
        assertEquals(
            LightClientItem("Sync refused: the trust anchor is 20 periods old, past the limit of 13", null, false),
            LightClientItem.from(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 13)),
        )
        assertEquals(
            LightClientItem("Sync refused: the trust anchor is 20 periods old", null, false),
            LightClientItem.from(LightClientStatus.StaleAnchor(anchorAgePeriods = 20, boundPeriods = 0)),
        )
        assertEquals(
            LightClientItem("No light client on this device", null, false),
            LightClientItem.from(LightClientStatus.Unavailable),
        )
    }
}
