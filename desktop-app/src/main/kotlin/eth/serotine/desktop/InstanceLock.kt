package eth.serotine.desktop

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * This user's one running Serotine: an exclusive lock on a file in the app's data directory,
 * held until [close] or until the process ends, when the OS releases it even after a crash.
 * Two instances on one data directory would both write the account list and the key store,
 * and, with the engine, open one database twice; the second one is refused instead.
 */
internal class InstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        /** The lock on [file], or null when another Serotine holds it. */
        fun acquire(file: File): InstanceLock? {
            file.parentFile?.mkdirs()
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null // this process holds it already
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return InstanceLock(channel, lock)
        }
    }
}
