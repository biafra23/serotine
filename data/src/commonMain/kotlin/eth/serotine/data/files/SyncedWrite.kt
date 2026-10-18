package eth.serotine.data.files

/**
 * Writes [bytes] to the file at [path], created or truncated, and flushes them to the disk
 * (fsync) before it returns: what kotlinx-io cannot do, as it exposes no fsync. Only the file's
 * data: its name, a new directory entry, is flushed by [syncDirectoryOf]. Throws when the file
 * cannot be written or flushed; what it wrote of it may then be on the disk.
 */
internal expect fun writeSynced(path: String, bytes: ByteArray)

/**
 * Flushes the entries of the directory holding [path] (fsync on the directory), so that a name
 * just created or moved there survives a power loss. Where a directory cannot be opened for it
 * (Windows), nothing is flushed, and the file system's own ordering is what there is. Never
 * throws.
 */
internal expect fun syncDirectoryOf(path: String)
