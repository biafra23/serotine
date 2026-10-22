package eth.serotine.data.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileDebugScreenTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-debug-${Random.nextLong().toULong()}")
    private val file = Path(directory, "debug-screen.json")

    private fun TestScope.setting() = FileDebugScreen(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun itIsOffUntilTurnedOnAndStaysSoAcrossARestart() = runTest {
        assertFalse(setting().observe().first())

        assertTrue(setting().set(true))
        assertTrue(setting().observe().first())

        assertTrue(setting().set(false))
        assertFalse(setting().observe().first())
    }

    @Test
    fun aDamagedOrNewerFileIsOff() = runTest {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"version":99,"enabled":true}""") }
        assertFalse(setting().observe().first())

        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertFalse(setting().observe().first())
    }
}
