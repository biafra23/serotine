package eth.serotine.data.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal actual fun writeSynced(path: String, bytes: ByteArray) {
    FileOutputStream(path).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
}

internal actual fun syncDirectoryOf(path: String) {
    val directory = File(path).absoluteFile.parentFile ?: return
    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        // A directory cannot be opened for it here (Windows).
    } catch (e: UnsupportedOperationException) {
        // Nor on a file system that has no channel for a directory.
    }
}
