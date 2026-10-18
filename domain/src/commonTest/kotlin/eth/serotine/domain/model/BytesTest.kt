package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BytesTest {

    @Test
    fun hexRoundTripsInLowerCase() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000f7f80ff", bytes.toHex())
        assertContentEquals(bytes, "000F7f80Ff".hexToBytes())
        assertContentEquals(ByteArray(0), "".hexToBytes())
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "+f".hexToBytes() }
    }

    @Test
    fun anAddressIsItsTwentyBytes() {
        assertEquals(
            "70997970c51812dc3a010c7d01b50e0d17dc79c8",
            Address.of("0x70997970C51812dc3A010C7d01b50e0d17dc79C8").toBytes().toHex(),
        )
    }

    @Test
    fun keccakIsEthereumsNotNistSha3() {
        // Keccak-256 of nothing, the code hash of every account without code; SHA3-256's is a7ffc6f8….
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            keccak256(ByteArray(0)).toHex(),
        )
    }
}
