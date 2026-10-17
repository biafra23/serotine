package eth.serotine.data.engine

import eth.serotine.domain.model.NetworkUpgrade
import io.myotis.api.UpgradeAdvisory
import io.myotis.api.UpgradePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class UpgradeAdvisoriesTest {

    @Test
    fun anAnnouncedUpgradeKeepsItsTimeAndForkId() {
        assertEquals(
            NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5),
            UpgradeAdvisory(UpgradePhase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5).toNetworkUpgrade(),
        )
    }

    @Test
    fun theApisUnknownsBecomeNull() {
        assertEquals(
            NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = null, reportingNetworks = 3),
            UpgradeAdvisory(UpgradePhase.ACTIVE, 0, "0x00000000", 3).toNetworkUpgrade(),
        )
    }

    @Test
    fun aFieldOutOfShapeIsUnknownAndNeverThrows() {
        assertEquals(
            NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, activationTime = null, forkId = "0x6c1d9423", 0),
            UpgradeAdvisory(UpgradePhase.ACTIVE, -5, "0x6C1D9423", -1).toNetworkUpgrade(),
        )
        assertEquals(null, UpgradeAdvisory(UpgradePhase.ACTIVE, 1, "6c1d9423", 1).toNetworkUpgrade()?.forkId)
        assertEquals(null, UpgradeAdvisory(UpgradePhase.ACTIVE, 1, null, 1).toNetworkUpgrade()?.forkId)
        assertEquals(null, UpgradeAdvisory(null, 1_791_294_816, "0x6c1d9423", 5).toNetworkUpgrade())
    }
}
