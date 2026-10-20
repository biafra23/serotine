package eth.serotine.data.keys

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray

/**
 * The envelope's primitives on each platform they run on (the JDK's on the desktop, libsodium's
 * and the system's on iOS): AES-256-GCM against the GCM specification's own test cases, so an
 * envelope sealed on one platform opens on another, and the password bytes and durable writes
 * as their contracts say.
 */
class EnvelopeCryptoTest {

    private class Gcm(val key: String, val nonce: String, val plaintext: String, val aad: String, val sealed: String)

    // The GCM specification's test cases 13, 14 and 16 (McGrew and Viega, AES-256): the
    // ciphertext followed by the 16-byte tag.
    private val vectors = listOf(
        Gcm(
            key = "00".repeat(32),
            nonce = "00".repeat(12),
            plaintext = "",
            aad = "",
            sealed = "530f8afbc74536b9a963b4f1c4cb738b",
        ),
        Gcm(
            key = "00".repeat(32),
            nonce = "00".repeat(12),
            plaintext = "00".repeat(16),
            aad = "",
            sealed = "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919",
        ),
        Gcm(
            key = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308",
            nonce = "cafebabefacedbaddecaf888",
            plaintext = "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
            aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2",
            sealed = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
        ),
    )

    private val directory = Path(SystemTemporaryDirectory, "serotine-envelope-crypto-${Random.nextLong().toULong()}")

    @AfterTest
    fun cleanUp() {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach(SystemFileSystem::delete)
        SystemFileSystem.delete(directory)
    }

    @Test
    fun aesGcmMatchesTheSpecificationsTestCases() {
        for (v in vectors) {
            val sealed = EnvelopeCrypto.aesGcmSeal(v.key.fromHex(), v.nonce.fromHex(), v.plaintext.fromHex(), v.aad.fromHex())
            assertEquals(v.sealed, sealed.toHex())
            val opened = EnvelopeCrypto.aesGcmOpen(v.key.fromHex(), v.nonce.fromHex(), v.sealed.fromHex(), v.aad.fromHex())
            assertEquals(v.plaintext, opened?.toHex())
        }
    }

    @Test
    fun aesGcmOpensNothingTampered() {
        val v = vectors.last()
        val key = v.key.fromHex()
        val nonce = v.nonce.fromHex()
        val sealed = v.sealed.fromHex()
        val aad = v.aad.fromHex()

        val flipped = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(EnvelopeCrypto.aesGcmOpen(key, nonce, flipped, aad))
        assertNull(EnvelopeCrypto.aesGcmOpen(key, nonce, sealed, aad + 0))
        assertNull(EnvelopeCrypto.aesGcmOpen(key.copyOf().also { it[31] = 0 }, nonce, sealed, aad))
        assertNull(EnvelopeCrypto.aesGcmOpen(key, nonce, sealed.copyOf(10), aad))
    }

    @Test
    fun aPasswordComposedEitherWayGivesTheSameBytes() {
        val composed = EnvelopeCrypto.passwordBytes("p\u00e4ss")
        assertContentEquals(composed, EnvelopeCrypto.passwordBytes("pa\u0308ss"))
        assertContentEquals("p\u00e4ss".encodeToByteArray(), composed)
        // Compatibility forms too: a full-width letter is the letter.
        assertContentEquals("A".encodeToByteArray(), EnvelopeCrypto.passwordBytes("\uff21"))
    }

    @Test
    fun randomBytesAreTheSizeAskedFor() {
        assertEquals(0, EnvelopeCrypto.randomBytes(0).size)
        val a = EnvelopeCrypto.randomBytes(32)
        assertEquals(32, a.size)
        assertTrue(!a.contentEquals(EnvelopeCrypto.randomBytes(32)))
    }

    @Test
    fun aDurableWriteReplacesOnlyWhenAskedTo() {
        EnvelopeCrypto.createPrivateDirectory(directory.toString())
        val file = Path(directory, "state.json").toString()

        EnvelopeCrypto.writeDurably(file, byteArrayOf(1, 2, 3), replace = false)
        assertContentEquals(byteArrayOf(1, 2, 3), read(file))

        assertFails { EnvelopeCrypto.writeDurably(file, byteArrayOf(4), replace = false) }
        assertContentEquals(byteArrayOf(1, 2, 3), read(file))

        EnvelopeCrypto.writeDurably(file, byteArrayOf(5, 6), replace = true)
        assertContentEquals(byteArrayOf(5, 6), read(file))

        // No temporary file is left behind, whichever way a write ended.
        assertEquals(listOf("state.json"), SystemFileSystem.list(directory).map { it.name })
    }

    private fun read(file: String): ByteArray = SystemFileSystem.source(Path(file)).buffered().use { it.readByteArray() }
}
