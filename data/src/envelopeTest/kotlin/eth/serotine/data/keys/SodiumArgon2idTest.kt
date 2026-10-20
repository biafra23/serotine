package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The envelope's Argon2id through its own byte-array mapping of libsodium (JNA on the desktop,
 * its own cinterop declaration on iOS), against libsodium's own known answers
 * (test/default/pwhash_argon2id.c and .exp, the first three `tv` cases): passwords of arbitrary bytes and odd lengths, so a wrong length or encoding
 * anywhere in the mapping shows.
 */
class SodiumArgon2idTest {

    private class Vector(
        val password: String,
        val salt: String,
        val outLength: Int,
        val opsLimit: Long,
        val memLimitBytes: Long,
        val expected: String,
    )

    private val vectors = listOf(
        Vector(
            password = "a347ae92bce9f80f6f595a4480fc9c2fe7e7d7148d371e9487d75f5c23008ffae065577a928febd9b1973a5a" +
                "95073acdbeb6a030cfc0d79caa2dc5cd011cef02c08da232d76d52dfbca38ca8dcbd665b17d1665f7cf5fe59" +
                "772ec909733b24de97d6f58d220b20c60d7c07ec1fd93c52c31020300c6c1facd77937a597c7a6",
            salt = "5541fbc995d5c197ba290346d2c559dedf405cf97e5f95482143202f9e74f5c2",
            outLength = 155,
            opsLimit = 5,
            memLimitBytes = 7256678,
            expected = "18acec5d6507739f203d1f5d9f1d862f7c2cdac4f19d2bdff64487e60d969e3ced615337b9eec6ac4461c6ca" +
                "07f0939741e57c24d0005c7ea171a0ee1e7348249d135b38f222e4dad7b9a033ed83f5ca27277393e3165820" +
                "33c74affe2566a2bea47f91f0fd9fe49ece7e1f79f3ad6e9b23e0277c8ecc4b313225748dd2a80f5679534a0" +
                "700e246a79a49b3f74eb89ec6205fe1eeb941c73b1fcf1",
        ),
        Vector(
            password = "e125cee61c8cb7778d9e5ad0a6f5d978ce9f84de213a8556d9ffe202020ab4a6ed9074a4eb3416f9b168f137" +
                "510f3a30b70b96cbfa219ff99f6c6eaffb15c06b60e00cc2890277f0fd3c622115772f7048adaebed86e",
            salt = "f1192dd5dc2368b9cd421338b22433455ee0a3699f9379a08b9650ea2c126f0d",
            outLength = 250,
            opsLimit = 4,
            memLimitBytes = 7849083,
            expected = "26bab5f101560e48c711da4f05e81f5a3802b7a93d5155b9cab153069cc42b8e9f910bfead747652a0708d70" +
                "e4de0bada37218bd203a1201c36b42f9a269b675b1f30cfc36f35a3030e9c7f57dfba0d341a974c1886f708c" +
                "3e8297efbfe411bb9d51375264bd7c70d57a8a56fc9de2c1c97c08776803ec2cd0140bba8e61dc0f4ad3d3d1" +
                "a89b4b710af81bfe35a0eea193e18a6da0f5ec05542c9eefc4584458e1da715611ba09617384748bd43b9bf1" +
                "f3a6df4ecd091d0875e08d6e2fd8a5c7ce08904b5160cd38167b76ec76ef2d310049055a564da23d4ebd2b87" +
                "e421cc33c401e12d5cd8d936c9baf75ebdfb557d342d2858fc781da31860",
        ),
        Vector(
            password = "92263cbf6ac376499f68a4289d3bb59e5a22335eba63a32e6410249155b956b6a3b48d4a44906b18b8971273" +
                "00b375b8f834f1ceffc70880a885f47c33876717e392be57f7da3ae58da4fd1f43daa7e44bb82d3717af4319" +
                "349c24cd31e46d295856b0441b6b289992a11ced1cc3bf3011604590244a3eb737ff221129215e4e4347f491" +
                "5d41292b5173d196eb9add693be5319fdadc242906178bb6c0286c9b6ca6012746711f58c8c392016b2fdfc0" +
                "9c64f0f6b6ab7b",
            salt = "3b840e20e9555e9fb031c4ba1f1747ce25cc1d0ff664be676b9b4a90641ff194",
            outLength = 249,
            opsLimit = 3,
            memLimitBytes = 7994791,
            expected = "6eb45e668582d63788ca8f6e930ca60b045a795fca987344f9a7a135aa3b5132b50a34a3864c26581f1f56dd" +
                "0bcbfafbfa92cd9bff6b24a734cfe88f854aef4bda0a7983120f44936e8ff31d29728ac08ccce6f3f916b3c6" +
                "3962755c23a1fa9bb4e8823fc867bfd18f28980d94bc5874423ab7f96cc0ab78d8fa21fbd00cd3a1d96a73fa" +
                "439ccc3fc4eab1590677b06cc78b0f674dfb680f23022fb902022dd8620803229c6ddf79a8156ccfce48bbd7" +
                "6c05ab670634f206e5b2e896230baa74a856964dbd8511acb71d75a1506766a125d8ce037f1db72086ebc3bc" +
                "caefbd8cd9380167c2530386544ebfbeadbe237784d102bb92a10fd242",
        ),
    )

    @Test
    fun libsodiumLoadsHere() {
        assertTrue(EnvelopeCrypto.available())
    }

    @Test
    fun matchesLibsodiumsKnownAnswers() {
        for (vector in vectors) {
            val out = EnvelopeCrypto.argon2id(
                vector.password.fromHex(),
                vector.salt.fromHex().copyOf(16),
                vector.opsLimit,
                vector.memLimitBytes,
                vector.outLength,
            )
            assertEquals(vector.expected, out.toHex())
        }
    }

    @Test
    fun everyByteOfANonAsciiPasswordCounts() {
        fun hash(password: String) = EnvelopeCrypto.argon2id(
            password.encodeToByteArray(), ByteArray(16), 2, 64L * 1024 * 1024, 32,
        ).toHex()
        // 8 chars, 9 UTF-8 bytes: the bindings' String API would hash only the first 8
        // bytes of each, the same for both.
        assertNotEquals(hash("pässword"), hash("pässworx"))
    }

    @Test
    fun theSaltIsLibsodiumsLengthOrRefused() {
        for (salt in listOf(ByteArray(0), ByteArray(8), ByteArray(17))) {
            assertFailsWith<IllegalArgumentException> {
                EnvelopeCrypto.argon2id(ByteArray(8), salt, 2, 64L * 1024 * 1024, 32)
            }
        }
    }
}
