package eth.serotine.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.open
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeSynced(path: String, bytes: ByteArray) {
    val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, NEW_FILE_MODE)
    if (fd < 0) throw IOException("could not open the file (errno $errno)")
    try {
        writeFully(fd, bytes)
        if (fsync(fd) != 0) throw IOException("could not flush the file (errno $errno)")
    } finally {
        close(fd)
    }
}

/** Writes all of [bytes] to the open file [fd], however many writes it takes. */
@OptIn(ExperimentalForeignApi::class)
internal fun writeFully(fd: Int, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    var written = 0
    bytes.usePinned { pinned ->
        while (written < bytes.size) {
            val n = write(fd, pinned.addressOf(written), (bytes.size - written).convert())
            if (n < 0) throw IOException("could not write the file (errno $errno)")
            written += n.toInt()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncDirectoryOf(path: String) {
    val directory = path.substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }
    val fd = open(directory, O_RDONLY)
    if (fd < 0) return
    fsync(fd)
    close(fd)
}

/** 0666 before the umask: the mode fopen gives a new file, as kotlinx-io's own writes have it. */
private const val NEW_FILE_MODE = 0x1B6
