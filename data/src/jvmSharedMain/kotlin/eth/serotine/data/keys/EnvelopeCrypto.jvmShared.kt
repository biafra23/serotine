package eth.serotine.data.keys

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual object EnvelopeCrypto {

    private val random = SecureRandom()

    actual fun available(): Boolean = Sodium.library != null

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    actual fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
        outLength: Int,
    ): ByteArray {
        val sodium = checkNotNull(Sodium.library) { "libsodium is not available" }
        val out = ByteArray(outLength)
        val result = sodium.crypto_pwhash(
            out,
            outLength.toLong(),
            password,
            password.size.toLong(),
            salt,
            opsLimit,
            Sodium.SizeT(memLimitBytes),
            sodium.crypto_pwhash_alg_argon2id13(),
        )
        // libsodium fails only for out-of-range parameters or when the memory cannot be had.
        check(result == 0) { "crypto_pwhash failed" }
        return out
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
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    actual fun writeDurably(path: String, bytes: ByteArray) {
        val target = File(path)
        val temporary = File(target.parentFile, "${target.name}.${randomBytes(6).toHex()}.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync() // the data is on the disk before the name points at it
            }
            Files.move(
                Paths.get(temporary.path),
                Paths.get(target.path),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}
