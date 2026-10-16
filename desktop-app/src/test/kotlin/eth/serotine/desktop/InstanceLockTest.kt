package eth.serotine.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceLockTest {

    private val directory: File = Files.createTempDirectory("serotine-instance").toFile()
    private val file = directory.resolve("data/serotine.lock")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun aSecondInstanceIsRefusedWhileTheFirstRuns() {
        val first = assertNotNull(InstanceLock.acquire(file), "the data directory is created too")

        assertEquals("held", probe(), "another process")
        assertNull(InstanceLock.acquire(file), "this process again")

        first.close()
        assertEquals("acquired", probe(), "free once the first one ends")
    }

    @Test
    fun aCrashedInstanceLeavesNothingBehindThatBlocks() {
        // The other process takes the lock and dies without releasing it: the file stays,
        // and the OS drops the lock with the process.
        assertEquals("acquired", probe(crash = true))
        assertTrue(file.exists())

        assertNotNull(InstanceLock.acquire(file)).close()
    }

    /** Asks another JVM to take the lock: what a second Serotine would see. */
    private fun probe(crash: Boolean = false): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classPath = System.getProperty("java.class.path")
        val process = ProcessBuilder(java, "-cp", classPath, LockProbe::class.java.name, file.path, crash.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe finishes")
        return output
    }
}

/**
 * The other process of [InstanceLockTest]: tries the lock once and says what happened; with
 * `true` as its second argument it then dies holding it, as a crash would.
 */
object LockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = InstanceLock.acquire(File(args[0]))
        println(if (lock == null) "held" else "acquired")
        System.out.flush()
        if (args[1].toBoolean()) Runtime.getRuntime().halt(0) // no release, no shutdown hooks
        lock?.close()
    }
}
