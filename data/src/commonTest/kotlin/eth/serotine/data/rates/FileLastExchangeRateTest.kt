package eth.serotine.data.rates

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RateSnapshot
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

@OptIn(ExperimentalCoroutinesApi::class)
class FileLastExchangeRateTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-rate-${Random.nextLong().toULong()}")
    private val file = Path(directory, "last-exchange-rate.json")

    private fun TestScope.store() = FileLastExchangeRate(file.toString(), StandardTestDispatcher(testScheduler))

    private val snapshot = RateSnapshot(
        base = PriceId("ETH"),
        quote = DisplayCurrency.EUR,
        rate = 2_000.5,
        dayAgoRate = 1_950.25,
        updatedAt = 1_700_000_000,
        readAt = 1_700_000_600,
        stale = false,
    )

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @Test
    fun noneIsKeptUntilOneIsSavedAndItSurvivesARestart() = runTest {
        assertNull(store().load(Network.SEPOLIA))

        assertTrue(store().save(Network.SEPOLIA, snapshot))
        assertTrue(store().save(Network.SEPOLIA, snapshot.copy(dayAgoRate = null, stale = true)))

        assertEquals(snapshot.copy(dayAgoRate = null, stale = true), store().load(Network.SEPOLIA))
    }

    @Test
    fun aCurrencyThisBuildDoesNotKnowOrNoRateIsNoneKept() = runTest {
        write(
            """{"version":1,"base":"ETH","quote":"XAU","rate":1.0,"dayAgoRate":null,""" +
                """"updatedAt":1,"readAt":2,"stale":false}""",
        )
        assertNull(store().load(Network.SEPOLIA))

        write("""{"version":1,"base":"ETH","quote":"EUR","rate":-1.0,"updatedAt":1,"readAt":2,"stale":false}""")
        assertNull(store().load(Network.SEPOLIA))
        assertTrue(SystemFileSystem.list(directory).none { it.name.contains("damaged") })
    }

    @Test
    fun aDamagedFileIsSetAsideAndANewerOneLeftAlone() = runTest {
        write("not json")
        assertNull(store().load(Network.SEPOLIA))
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") })

        cleanUp()
        write("""{"version":3,"base":"ETH"}""")
        assertNull(store().load(Network.SEPOLIA))
        assertFalse(store().save(Network.SEPOLIA, snapshot))
        assertTrue(SystemFileSystem.list(directory).size == 1)
    }

    @Test
    fun theRateOfAFileFromBeforeNetworksIsSepolias() = runTest {
        write(
            """{"version":1,"base":"ETH","quote":"EUR","rate":2000.5,"dayAgoRate":1950.25,""" +
                """"updatedAt":1700000000,"readAt":1700000600,"stale":false}""",
        )

        assertEquals(snapshot, store().load(Network.SEPOLIA))
    }

    @Test
    fun aRateOfANetworkThisBuildDoesNotKnowIsKeptAsideBeforeTheNextSave() = runTest {
        write(
            """{"version":2,"rates":[{"chain":424242,"base":"ETH","quote":"EUR","rate":1.0,"dayAgoRate":null,""" +
                """"updatedAt":1,"readAt":2,"stale":false}]}""",
        )

        assertNull(store().load(Network.SEPOLIA))
        assertTrue(store().save(Network.SEPOLIA, snapshot))
        assertEquals(snapshot, store().load(Network.SEPOLIA))
        assertTrue(
            SystemFileSystem.list(directory).any { it.name.contains("damaged") },
            "the other network's rate is kept",
        )
    }
}
