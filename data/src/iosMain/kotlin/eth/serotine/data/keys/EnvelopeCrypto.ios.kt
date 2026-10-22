package eth.serotine.data.keys

import eth.serotine.data.files.syncDirectoryOf
import eth.serotine.data.files.writeFully
import eth.serotine.data.keys.sodium.crypto_aead_aes256gcm_decrypt
import eth.serotine.data.keys.sodium.crypto_aead_aes256gcm_encrypt
import eth.serotine.data.keys.sodium.crypto_aead_aes256gcm_is_available
import eth.serotine.data.keys.sodium.crypto_pwhash
import eth.serotine.data.keys.sodium.crypto_pwhash_alg_argon2id13
import eth.serotine.data.keys.sodium.sodium_init
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.EEXIST
import platform.posix.EINVAL
import platform.posix.ENOTSUP
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.link
import platform.posix.open
import platform.posix.rename
import platform.posix.unlink

/**
 * The envelope's primitives on iOS: libsodium (the bindings' libsodium.a, 1.0.19) for Argon2id
 * and AES-256-GCM, which it runs on the ARMv8 crypto extensions every iPhone this app runs on
 * has; Security.framework's generator for randomness; POSIX for the durable write, with Apple's
 * F_FULLFSYNC, since fsync alone leaves the data in the drive's cache. libsodium's AES-256-GCM
 * output is the JDK's: the ciphertext with the 16-byte tag appended, so an envelope sealed on
 * one platform has the same shape as on the others.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object EnvelopeCrypto {

    private val ready: Boolean by lazy {
        // 0: initialized now, 1: already initialized; -1: failed.
        sodium_init() >= 0 && crypto_aead_aes256gcm_is_available() == 1
    }

    actual fun available(): Boolean = ready

    actual fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        if (size == 0) return bytes
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed ($status)" }
        return bytes
    }

    @Suppress("CAST_NEVER_SUCCEEDS") // a Kotlin String is an NSString on iOS
    actual fun passwordBytes(text: String): ByteArray =
        (text as NSString).precomposedStringWithCompatibilityMapping.encodeToByteArray()

    actual fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        outLength: Int,
    ): ByteArray {
        require(salt.size == Envelope.SALT_BYTES) { "libsodium reads a ${Envelope.SALT_BYTES}-byte salt" }
        check(ready) { "libsodium is not available" }
        // The arrays are pinned, not copied: libsodium reads the caller's own bytes, which the
        // caller zeroes, and no native copy is left behind.
        val out = ByteArray(outLength)
        val result = out.withBytes { outPtr ->
            password.withBytes { passwordPtr ->
                salt.withBytes { saltPtr ->
                    crypto_pwhash(
                        outPtr,
                        outLength.convert(),
                        passwordPtr,
                        password.size.convert(),
                        saltPtr,
                        opsLimit.convert(),
                        memLimitBytes.convert(),
                        crypto_pwhash_alg_argon2id13(),
                    )
                }
            }
        }
        // As on the JVM: every caller's parameters are within libsodium's bounds, so a failure
        // is the memory.
        if (result != 0) {
            out.fill(0)
            throw Argon2idOutOfMemory()
        }
        return out
    }

    actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        requireAesGcm(key, nonce)
        val out = ByteArray(plaintext.size + TAG_BYTES)
        val result = out.withBytes { outPtr ->
            plaintext.withBytes { inPtr ->
                aad.withBytes { aadPtr ->
                    nonce.withBytes { noncePtr ->
                        key.withBytes { keyPtr ->
                            crypto_aead_aes256gcm_encrypt(
                                outPtr, null,
                                inPtr, plaintext.size.convert(),
                                aadPtr, aad.size.convert(),
                                null, noncePtr, keyPtr,
                            )
                        }
                    }
                }
            }
        }
        check(result == 0) { "AES-256-GCM sealing failed" }
        return out
    }

    actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? {
        requireAesGcm(key, nonce)
        if (ciphertext.size < TAG_BYTES) return null
        val out = ByteArray(ciphertext.size - TAG_BYTES)
        val result = out.withBytes { outPtr ->
            ciphertext.withBytes { inPtr ->
                aad.withBytes { aadPtr ->
                    nonce.withBytes { noncePtr ->
                        key.withBytes { keyPtr ->
                            crypto_aead_aes256gcm_decrypt(
                                outPtr, null,
                                null,
                                inPtr, ciphertext.size.convert(),
                                aadPtr, aad.size.convert(),
                                noncePtr, keyPtr,
                            )
                        }
                    }
                }
            }
        }
        return if (result == 0) out else null
    }

    private fun requireAesGcm(key: ByteArray, nonce: ByteArray) {
        require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
        check(ready) { "libsodium's AES-256-GCM is not available" }
    }

    actual fun createPrivateDirectory(path: String) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val created = NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = error.ptr,
            )
            if (!created) throw IOException("could not create the directory (${error.value?.code ?: "no error"})")
        }
        // Set every time, as on the JVM: owner only, which the app's sandbox makes the app only.
        if (chmod(path, OWNER_ONLY_DIRECTORY.convert()) != 0) {
            throw IOException("could not restrict the directory (errno $errno)")
        }
        // Out of iCloud and computer backups (docs/keys-and-signing.md, rule 8): the envelopes
        // are useless off this device anyway, their hardware factor being this one's enclave.
        val excluded = NSURL.fileURLWithPath(path, isDirectory = true)
            .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        if (!excluded) throw IOException("could not exclude the directory from backups")
    }

    actual fun writeDurably(path: String, bytes: ByteArray, replace: Boolean) {
        val temporary = "$path.${randomBytes(6).toHex()}$TEMPORARY_SUFFIX"
        try {
            val fd = open(temporary, O_WRONLY or O_CREAT or O_EXCL, OWNER_ONLY_FILE)
            if (fd < 0) throw IOException("could not create the file (errno $errno)")
            try {
                writeFully(fd, bytes)
                fullSync(fd)
            } finally {
                close(fd)
            }
            if (replace) {
                if (rename(temporary, path) != 0) throw IOException("could not replace the file (errno $errno)")
            } else {
                // link(2) refuses an existing name, where rename(2) would replace it.
                if (link(temporary, path) != 0) {
                    val cause = errno
                    throw IOException(
                        if (cause == EEXIST) "the file exists already" else "could not store the file (errno $cause)",
                    )
                }
            }
            syncDirectoryOf(path)
        } finally {
            unlink(temporary) // gone already after a rename; the leftover name after a link
        }
    }

    /**
     * Flushes [fd] to the disk itself, not its cache, before a name points at it: F_FULLFSYNC,
     * and fsync only where the file system does not take F_FULLFSYNC. Any other failure is the
     * write's.
     */
    private fun fullSync(fd: Int) {
        if (fcntl(fd, F_FULLFSYNC) == 0) return
        val cause = errno
        if (cause != ENOTSUP && cause != EINVAL) throw IOException("could not flush the file (errno $cause)")
        if (fsync(fd) != 0) throw IOException("could not flush the file (errno $errno)")
    }

    /** libsodium's tag length for AES-256-GCM (crypto_aead_aes256gcm_ABYTES). */
    private const val TAG_BYTES = 16

    /** fcntl.h's F_FULLFSYNC on Darwin. */
    private const val F_FULLFSYNC = 51

    private const val OWNER_ONLY_DIRECTORY = 0x1C0 // 0700

    private const val OWNER_ONLY_FILE = 0x180 // 0600
}

/**
 * [block] with a pointer to this array's bytes, pinned for the call, or a null pointer for an
 * empty array (libsodium takes a null pointer with a zero length).
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> ByteArray.withBytes(block: (CPointer<UByteVar>?) -> R): R =
    if (isEmpty()) block(null) else usePinned { block(it.addressOf(0).reinterpret()) }
