package eth.serotine.data.files

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
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

/**
 * [VersionedJsonFile] keeping a file it could read only in part: the file is never left missing,
 * whether the rewrite fails or the reader is cancelled meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VersionedJsonFileTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-json-${Random.nextLong().toULong()}")
    private val path = Path(directory, "items.json")

    /** Two good items and one this build cannot read. */
    private val partial = """{"version":1,"items":["a","bad","b"]}"""

    /** Whether writing fails, as on a full disk. */
    private var failWrites = false

    /** Whether a read passed an item over: one was in the text and is not in what was read. */
    private val passedOver: (String, List<String>) -> Boolean = { text, items -> "bad" in text && items.size < 3 }

    /** How a file is written: [writeSynced], unless a test breaks it. */
    private var writeFile: (String, ByteArray) -> Unit = ::writeSynced

    private fun TestScope.file(incomplete: (String, List<String>) -> Boolean = passedOver) =
        VersionedJsonFile(
            path.toString(),
            StandardTestDispatcher(testScheduler),
            what = "items",
            formatVersion = 1,
            empty = emptyList(),
            decode = { _, text -> ITEM.findAll(text).map { it.groupValues[1] }.filter { it != "bad" }.toList() },
            encode = { items ->
                if (failWrites) throw IOException("disk full")
                """{"version":1,"items":[${items.joinToString(",") { "\"$it\"" }}]}"""
            },
            incomplete = incomplete,
            writeFile = { path, bytes -> writeFile(path, bytes) },
        )

    private fun written(file: Path = path) = SystemFileSystem.source(file).buffered().use { it.readString() }

    private fun copies() = SystemFileSystem.list(directory).filter { ".damaged-" in it.name }

    private fun seed() {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(path).buffered().use { it.writeString(partial) }
    }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun aFileReadInPartIsCopiedAsideOnceAndRewrittenWithWhatWasRead() = runTest {
        seed()

        val read = file().observe().first()

        assertEquals(VersionedJsonFile.Read.Loaded(listOf("a", "b"), damagedSetAside = true), read)
        assertEquals(partial, written(copies().single()), "the item passed over is in the copy")
        assertEquals("""{"version":1,"items":["a","b"]}""", written())
    }

    @Test
    fun aRewriteThatFailsLeavesTheFileAsItWasAndNoCopy() = runTest {
        seed()
        failWrites = true

        val read = file().observe().first()

        assertEquals(VersionedJsonFile.Read.Loaded(listOf("a", "b")), read, "what was read is used")
        assertEquals(partial, written(), "never moved, never lost: every item is still in the file")
        assertEquals(emptyList(), copies())
    }

    @Test
    fun aCopyThatFailsPartwayIsDeletedAndTheFileStaysAsItWas() = runTest {
        seed()
        // The disk fills while the copy is written: half of it lands, then the write fails.
        writeFile = { target, bytes ->
            if (".damaged-" in target) {
                writeSynced(target, bytes.copyOf(bytes.size / 2))
                throw IOException("disk full")
            }
            writeSynced(target, bytes)
        }

        val read = file().observe().first()

        assertEquals(VersionedJsonFile.Read.Loaded(listOf("a", "b")), read, "what was read is used")
        assertEquals(emptyList(), copies(), "no truncated copy is left behind")
        assertEquals(partial, written(), "the file is not rewritten without its copy")
    }

    @Test
    fun aReaderCancelledMeanwhileStillLeavesTheFileAndItsCopyWhole() = runTest {
        seed()
        lateinit var reader: Job
        // The reader goes away just as the file is found incomplete: the copy and the rewrite run on.
        val file = file { text, items ->
            reader.cancel()
            "bad" in text && items.size < 3
        }

        reader = launch { file.observe().first() }
        advanceUntilIdle()

        assertTrue(reader.isCancelled)
        assertEquals(partial, written(copies().single()))
        assertEquals("""{"version":1,"items":["a","b"]}""", written())
    }

    private companion object {
        val ITEM = Regex(""""([a-z]+)"(?=[,\]])""")
    }
}
