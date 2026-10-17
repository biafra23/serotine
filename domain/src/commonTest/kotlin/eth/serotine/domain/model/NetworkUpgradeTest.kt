package eth.serotine.domain.model

import eth.serotine.domain.model.NetworkUpgrade.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkUpgradeTest {

    private val scheduled = NetworkUpgrade(Phase.SCHEDULED, activationTime = 1_791_294_816, "0x6c1d9423", 5)

    @Test
    fun anInvalidReportCannotExist() {
        assertFailsWith<IllegalArgumentException> { scheduled.copy(activationTime = 0) }
        assertFailsWith<IllegalArgumentException> { scheduled.copy(forkId = "0x6C1D9423") }
        assertFailsWith<IllegalArgumentException> { scheduled.copy(forkId = "6c1d9423") }
        assertFailsWith<IllegalArgumentException> { scheduled.copy(reportingNetworks = -1) }
    }

    @Test
    fun itHasActivatedWhenReportedActiveOrOnceItsTimeHasCome() {
        assertFalse(scheduled.hasActivated(now = 1_791_294_815))
        assertTrue(scheduled.hasActivated(now = 1_791_294_816))
        assertTrue(scheduled.copy(phase = Phase.ACTIVE).hasActivated(now = 0))
        assertFalse(scheduled.copy(activationTime = null).hasActivated(now = Long.MAX_VALUE))
    }

    @Test
    fun aReportWithoutATimeIsTheSameUpgradeAndAnotherTimeIsAnotherOne() {
        val placed = NetworkUpgrade(Phase.ACTIVE, activationTime = null, "0x6c1d9423", 4)
        assertTrue(scheduled.isSameUpgradeAs(placed))
        assertTrue(placed.isSameUpgradeAs(scheduled))
        assertTrue(scheduled.isSameUpgradeAs(scheduled.copy(reportingNetworks = 9)))
        assertFalse(scheduled.isSameUpgradeAs(scheduled.copy(activationTime = 1_791_381_216)))
    }

    @Test
    fun aLaterReportKeepsWhatTheEarlierOneKnew() {
        val placed = NetworkUpgrade(Phase.ACTIVE, activationTime = null, forkId = null, 7)

        assertEquals(
            NetworkUpgrade(Phase.ACTIVE, 1_791_294_816, "0x6c1d9423", 7),
            scheduled.updatedBy(placed),
        )
        // Once active, a later report that only announces it does not make it scheduled again.
        assertEquals(Phase.ACTIVE, scheduled.updatedBy(placed).updatedBy(scheduled).phase)
    }
}
