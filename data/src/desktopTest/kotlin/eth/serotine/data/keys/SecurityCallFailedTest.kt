package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityCallFailedTest {

    private val la = "com.apple.LocalAuthentication"

    @Test
    fun thePromptsCodeIsFoundAtTheTopOrUnderneath() {
        assertEquals(-2L, SecurityCallFailed("x", listOf(SecurityCallFailed.Cause(la, -2))).localAuthentication)
        val nested = listOf(SecurityCallFailed.Cause("CryptoTokenKit", -4), SecurityCallFailed.Cause(la, -3))
        assertEquals(-3L, SecurityCallFailed("x", nested).localAuthentication)
    }

    @Test
    fun securitysOwnUserCancelIsACancel() {
        val cancelled = listOf(SecurityCallFailed.Cause("NSOSStatusErrorDomain", -128))
        assertEquals(LaError.USER_CANCEL, SecurityCallFailed("x", cancelled).localAuthentication)
    }

    @Test
    fun anyOtherErrorIsNotThePrompts() {
        assertNull(SecurityCallFailed("x", listOf(SecurityCallFailed.Cause("NSOSStatusErrorDomain", -25300))).localAuthentication)
        assertNull(SecurityCallFailed("x", emptyList()).localAuthentication)
    }

    @Test
    fun itsMessageNamesTheCallAndItsErrors() {
        assertEquals("x failed (no error)", SecurityCallFailed("x", emptyList()).message)
        assertEquals("x failed (-2 in $la)", SecurityCallFailed("x", listOf(SecurityCallFailed.Cause(la, -2))).message)
    }
}
