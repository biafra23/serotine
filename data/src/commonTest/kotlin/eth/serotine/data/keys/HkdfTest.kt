package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 5869, appendix A, the SHA-256 cases 1 and 3. */
class HkdfTest {

    @Test
    fun rfc5869TestCase1() {
        val okm = Hkdf.sha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun rfc5869TestCase3WithoutSaltOrInfo() {
        val okm = Hkdf.sha256(ikm = ByteArray(22) { 0x0b }, salt = ByteArray(0), info = ByteArray(0), length = 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }
}
