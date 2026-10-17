package eth.serotine.data.settings

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.repository.CurrencyChoice
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileDisplayCurrencyTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-currency-${Random.nextLong().toULong()}")
    private val file = Path(directory, "display-currency.json")

    private fun TestScope.setting() = FileDisplayCurrency(file.toString(), StandardTestDispatcher(testScheduler))

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
        assertEquals(CurrencyChoice.Chosen(null), setting().observe().first())

        assertTrue(setting().choose(DisplayCurrency.EUR))

        assertEquals(CurrencyChoice.Chosen(DisplayCurrency.EUR), setting().observe().first())
    }

    @Test
    fun aCurrencyThisBuildDoesNotKnowIsNoChoiceNotDamage() = runTest {
        write("""{"version":1,"currency":"XAU"}""")

        assertEquals(CurrencyChoice.Chosen(null), setting().observe().first())
        assertTrue(SystemFileSystem.list(directory).none { it.name.contains("damaged") })
    }

    @Test
    fun aNewerFormatIsUnreadableAndCannotBeOverwritten() = runTest {
        write("""{"version":2,"currency":"EUR"}""")
        val setting = setting()

        assertEquals(CurrencyChoice.Unreadable, setting.observe().first())
        assertEquals(false, setting.choose(DisplayCurrency.GBP))
    }
}
