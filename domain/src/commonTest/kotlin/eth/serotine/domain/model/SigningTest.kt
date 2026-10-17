package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SigningTest {

    private val order = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
    private val halfOrder = "7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"
    private val one = "0".repeat(63) + "1"

    @Test
    fun aDigestIsThirtyTwoBytesOfHexInAnyCaseWithOrWithoutPrefix() {
        assertEquals("ab".repeat(32), Digest.of("0x" + "AB".repeat(32)).hex)
        assertNull(Digest.parse("ab".repeat(31)))
        assertNull(Digest.parse("zz".repeat(32)))
        assertFailsWith<IllegalArgumentException> { Digest.of("0x") }
    }

    @Test
    fun aSignatureHoldsItsPromisesFromConstruction() {
        Signature(r = one, s = halfOrder, recoveryId = 1)
        Signature(r = order.dropLast(1) + "0", s = one, recoveryId = 0)

        val refused = listOf(
            "r of 31 bytes" to { Signature(one.drop(2), one, 0) },
            "upper-case hex" to { Signature("A".repeat(64), one, 0) },
            "not hex" to { Signature("g".repeat(64), one, 0) },
            "r of zero" to { Signature("0".repeat(64), one, 0) },
            "r of n" to { Signature(order, one, 0) },
            "s of zero" to { Signature(one, "0".repeat(64), 0) },
            // The high-s twin of a valid signature: Ethereum refuses it, and so does this type.
            "s above n/2" to { Signature(one, halfOrder.dropLast(1) + "1", 0) },
            "recovery id 2" to { Signature(one, one, 2) },
        )
        refused.forEach { (case, make) -> assertFailsWith<IllegalArgumentException>(case) { make() } }
    }
}
