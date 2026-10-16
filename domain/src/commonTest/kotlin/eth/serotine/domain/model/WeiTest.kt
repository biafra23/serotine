package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WeiTest {

    @Test
    fun acceptsNonNegativeDecimalIntegersOfAnySize() {
        assertEquals("0", Wei.of("0").decimal)
        // More than a Long holds: 2^64 + 1.
        assertEquals("18446744073709551617", Wei.of("18446744073709551617").decimal)
    }

    @Test
    fun refusesAnythingElse() {
        listOf("", "-1", "01", "1.5", "0x10", " 1", "1e18").forEach { assertNull(Wei.parse(it), it) }
        assertFailsWith<IllegalArgumentException> { Wei.of("abc") }
    }
}
