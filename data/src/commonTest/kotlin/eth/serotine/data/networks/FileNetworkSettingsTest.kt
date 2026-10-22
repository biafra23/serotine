package eth.serotine.data.networks

import eth.serotine.domain.model.Network
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

/** [FileNetworkSettings] against the [eth.serotine.domain.repository.NetworkSettings] contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileNetworkSettingsTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-networks-${Random.nextLong().toULong()}")
    private val file = Path(directory, "networks.json")

    private fun TestScope.settings(defaultOn: (Network) -> Boolean = { true }) =
        FileNetworkSettings(file.toString(), defaultOn, StandardTestDispatcher(testScheduler))

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

    private fun text() = SystemFileSystem.source(file).buffered().use { it.readString() }

    @Test
    fun aNetworkTheFileDoesNotMentionGetsTheBuildsDefaultWhichIsRecorded() = runTest {
        assertEquals(setOf(Network.SEPOLIA), settings { it == Network.SEPOLIA }.observe().first())
        assertEquals("""{"version":1,"networks":[{"chain":11155111,"on":true}]}""", text())
    }

    @Test
    fun theUsersChoiceStandsWhateverTheDefault() = runTest {
        assertTrue(settings().setEnabled(Network.SEPOLIA, on = false))

        assertEquals(emptySet(), settings { true }.observe().first(), "an update does not reset it")
        assertTrue(settings().setEnabled(Network.SEPOLIA, on = true))
        assertEquals(setOf(Network.SEPOLIA), settings { false }.observe().first())
    }

    @Test
    fun aRecordedNetworkKeepsItsSettingAndAnUnknownOneIsKeptAside() = runTest {
        write("""{"version":1,"networks":[{"chain":11155111,"on":false},{"chain":424242,"on":true}]}""")

        assertEquals(emptySet(), settings { true }.observe().first())
        assertTrue(SystemFileSystem.list(directory).any { it.name.contains("damaged") }, "the unknown entry is kept")
    }

    @Test
    fun aFileThatCannotBeReadIsAnsweredWithTheDefaultsAndNotWrittenOver() = runTest {
        val newer = """{"version":2,"networks":[]}"""
        write(newer)

        assertEquals(setOf(Network.SEPOLIA), settings { true }.observe().first())
        assertEquals(newer, text())
    }
}
