package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AddressTest {

    @Test
    fun parseNormalisesToLowerCaseAndTrims() {
        val parsed = Address.parse("  0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045 ")
        assertEquals("0xd8da6bf26964af9d7eed9e03e53415d37aa96045", parsed?.hex)
    }

    @Test
    fun parseRefusesEverythingThatIsNotTwentyHexBytes() {
        assertNull(Address.parse(""))
        assertNull(Address.parse("d8da6bf26964af9d7eed9e03e53415d37aa96045")) // no 0x
        assertNull(Address.parse("0xd8da6bf26964af9d7eed9e03e53415d37aa9604")) // 39 digits
        assertNull(Address.parse("0xd8da6bf26964af9d7eed9e03e53415d37aa960455")) // 41 digits
        assertNull(Address.parse("0xd8da6bf26964af9d7eed9e03e53415d37aa9604g")) // not hex
    }

    @Test
    fun ofThrowsOnBadInput() {
        assertFailsWith<IllegalArgumentException> { Address.of("vitalik.eth") }
    }

    @Test
    fun equalityIsCaseInsensitiveBecauseConstructionNormalises() {
        assertEquals(
            Address.of("0xD8DA6BF26964AF9D7EED9E03E53415D37AA96045"),
            Address.of("0xd8da6bf26964af9d7eed9e03e53415d37aa96045"),
        )
    }
}
