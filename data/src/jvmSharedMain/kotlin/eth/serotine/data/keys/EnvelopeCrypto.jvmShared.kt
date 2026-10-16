package eth.serotine.data.keys

import com.sun.jna.Memory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object EnvelopeCrypto {

    private val random = SecureRandom()

    private val posix: Boolean by lazy { "posix" in FileSystems.getDefault().supportedFileAttributeViews() }

    actual fun available(): Boolean = Sodium.library != null

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    actual fun passwordBytes(text: String): ByteArray =
        Normalizer.normalize(text, Normalizer.Form.NFKC).encodeToByteArray()

    actual fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        outLength: Int,
    ): ByteArray {
        require(salt.size == Envelope.SALT_BYTES) { "libsodium reads a ${Envelope.SALT_BYTES}-byte salt" }
        val sodium = checkNotNull(Sodium.library) { "libsodium is not available" }
        // Native buffers of our own, zeroed below: JNA would copy byte arrays into
        // temporary native memory and free it without zeroing it. (Memory cannot be empty.)
        val passwordIn = Memory(maxOf(1, password.size).toLong())
        val saltIn = Memory(salt.size.toLong())
        val out = Memory(outLength.toLong())
        try {
            passwordIn.write(0, password, 0, password.size)
            saltIn.write(0, salt, 0, salt.size)
            val result = sodium.crypto_pwhash(
                out,
                outLength.toLong(),
                passwordIn,
                password.size.toLong(),
                saltIn,
                opsLimit,
                Sodium.SizeT(memLimitBytes),
                sodium.crypto_pwhash_alg_argon2id13(),
            )
            // libsodium fails only for out-of-range parameters or when the memory cannot be had.
            check(result == 0) { "crypto_pwhash failed" }
            return out.getByteArray(0, outLength)
        } finally {
            passwordIn.clear()
            out.clear()
            passwordIn.close()
            saltIn.close()
            out.close()
        }
    }

    actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
        null
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        // SecretKeySpec copies the key, and the JDK's AES keeps its key schedule, until the
        // garbage collector takes them: the GC caveat of rule 7.
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    actual fun createPrivateDirectory(path: String) {
        val directory = Paths.get(path)
        Files.createDirectories(directory)
        // Set every time, not only on creation: a directory an earlier version created keeps
        // whatever the umask gave it otherwise.
        if (posix) Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY)
    }

    actual fun writeDurably(path: String, bytes: ByteArray, replace: Boolean) {
        val target = File(path)
        val temporary = File(target.parentFile, "${target.name}.${randomBytes(6).toHex()}$TEMPORARY_SUFFIX")
        try {
            if (posix) {
                Files.createFile(temporary.toPath(), PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
            }
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync() // the data is on the disk before the name points at it
            }
            if (replace) {
                Files.move(
                    Paths.get(temporary.path),
                    Paths.get(target.path),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                // Not ATOMIC_MOVE, which is rename(2) on POSIX and replaces whatever is there:
                // without it the JDK refuses an existing target, then renames. The callers hold
                // the key store's lock, and the desktop runs one instance, so nothing gets in
                // between.
                Files.move(Paths.get(temporary.path), Paths.get(target.path))
            }
            syncDirectory(target.parentFile)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Flushes [directory]'s entries, so the moved name survives a power loss too. Linux and
     * Android allow it; where a directory cannot be opened for it (Windows), the move's own
     * guarantees are what there is.
     */
    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // not supported for directories here
        }
    }

    private val OWNER_ONLY_DIRECTORY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    private val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
}
