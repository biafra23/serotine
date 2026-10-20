package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [SafeEvents.decode] on the events as Safe 1.3.0 and 1.4.1 log them. */
class SafeEventsTest {

    private val alice = Address.of("0x" + "11".repeat(20))
    private val bob = Address.of("0x" + "22".repeat(20))
    private val hash = "0x" + "ab".repeat(32)

    private fun word(address: Address) = "000000000000000000000000" + address.hex.removePrefix("0x")
    private fun word(value: Long) = value.toString(16).padStart(64, '0')
    private fun topic(address: Address) = "0x" + word(address)

    @Test
    fun anExecutionIsReadWithItsHashIndexedOrInTheData() {
        val success = "0x442e715f626346e8c54381002da614f62bee8d27386535b2521ec8540898556e"
        val failure = "0x23428b18acfb3ea64b08dc0c1d296ea9c09702c09083ca5272e64d115b687d23"

        // 1.4.1: the hash indexed, the payment in the data.
        assertEquals(SafeEvent.Executed(hash, succeeded = true), SafeEvents.decode(listOf(success, hash), "0x" + word(0)))
        // 1.3.0: both in the data.
        assertEquals(
            SafeEvent.Executed(hash, succeeded = false),
            SafeEvents.decode(listOf(failure), "0x" + hash.removePrefix("0x") + word(0)),
        )
        assertNull(SafeEvents.decode(listOf(success), "0x" + word(0)), "neither shape")
        assertNull(SafeEvents.decode(listOf(success, hash), "0x"), "no payment")
    }

    @Test
    fun anOwnerChangeIsReadWithItsAddressIndexedOrInTheData() {
        val added = "0x9465fa0c962cc76958e6373a993326400c1c94f8be2fe3a952adfa7f60b2ea26"

        assertEquals(SafeEvent.OwnerAdded(bob), SafeEvents.decode(listOf(added, topic(bob)), "0x"))
        assertEquals(SafeEvent.OwnerAdded(bob), SafeEvents.decode(listOf(added), "0x" + word(bob)))
        assertNull(SafeEvents.decode(listOf(added, topic(bob)), "0x" + word(bob)), "both")
        assertNull(SafeEvents.decode(listOf(added, "0x" + "ff".repeat(32)), "0x"), "not an address")
    }

    @Test
    fun receiptsApprovalsAndThresholds() {
        val received = "0x3d0ce9bfc3ed7d6862dbb28b2dea94561fe714a1b4d019aa8af39730d1ad7c3d"
        val approved = "0xf2a0eb156472d1440255b0d7c1e19cc07115d1051fe605b0dce69acfec884d9c"
        val threshold = "0x610f7ff2b304ae8903c3de74c60c6ab1f7d6226b3f52c5161905bb5ad4039c93"

        assertEquals(
            SafeEvent.Received(alice, UInt256.of(1_000_000_000_000_000)),
            SafeEvents.decode(listOf(received, topic(alice)), "0x" + word(1_000_000_000_000_000)),
        )
        assertEquals(SafeEvent.Approved(hash, bob), SafeEvents.decode(listOf(approved, hash, topic(bob)), "0x"))
        assertEquals(SafeEvent.ThresholdChanged(2), SafeEvents.decode(listOf(threshold), "0x" + word(2)))
    }

    @Test
    fun aSetUpIsReadWithItsOwnersAndThreshold() {
        val setUp = "0x141df868a6331af528e38c83b7aa03edc19be66e37ae67f9285bf4f8e3c6a1a8"
        val handler = Address.of("0xfd0732dc9e303f09fcef3a7388ad10a83459ec99")
        // Offset 0x80, threshold 2, initializer none, the handler, then two owners.
        val data = "0x" + word(0x80) + word(2) + word(Address.ZERO) + word(handler) + word(2) + word(alice) + word(bob)

        assertEquals(SafeEvent.SetUp(alice, listOf(alice, bob), 2), SafeEvents.decode(listOf(setUp, topic(alice)), data))
        assertNull(SafeEvents.decode(listOf(setUp, topic(alice)), data + word(1)), "more words than owners")
    }

    @Test
    fun anotherEventOrMalformedHexIsNoSafeEvent() {
        val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        assertNull(SafeEvents.decode(listOf(transfer, topic(alice), topic(bob)), "0x" + word(1)))
        assertNull(SafeEvents.decode(emptyList(), "0x"))
        assertNull(SafeEvents.decode(listOf("0x9465fa0c962cc76958e6373a993326400c1c94f8be2fe3a952adfa7f60b2ea26"), "0xzz"))
    }
}
