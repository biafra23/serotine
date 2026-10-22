package eth.serotine.data.settings

import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.VenueChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSwapVenueTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-venue-${Random.nextLong().toULong()}")
    private val file = Path(directory, "swap-venue.json")

    private fun TestScope.setting() = FileSwapVenue(file.toString(), StandardTestDispatcher(testScheduler))

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
    fun noneIsChosenUntilOneIsAndTheChoiceSurvivesARestart() = runTest {
        assertEquals(VenueChoice.Chosen(null), setting().observe().first())

        assertTrue(setting().choose(SwapVenue.COW))

        assertEquals(VenueChoice.Chosen(SwapVenue.COW), setting().observe().first())
        val text = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertEquals("""{"version":1,"venue":"cow"}""", text)
    }

    @Test
    fun aVenueThisBuildDoesNotKnowIsNoChoiceNotDamage() = runTest {
        write("""{"version":1,"venue":"elsewhere"}""")
        assertEquals(VenueChoice.Chosen(null), setting().observe().first())
        assertTrue(SystemFileSystem.list(directory).none { it.name.contains("damaged") })
    }

    @Test
    fun aNewerFormatIsUnreadableAndADamagedFileSetAside() = runTest {
        write("""{"version":2,"venue":"cow"}""")
        assertEquals(VenueChoice.Unreadable, setting().observe().first())

        write("""{"version":1,"venue":""")
        assertEquals(VenueChoice.Chosen(null, setAside = true), setting().observe().first())
    }
}
