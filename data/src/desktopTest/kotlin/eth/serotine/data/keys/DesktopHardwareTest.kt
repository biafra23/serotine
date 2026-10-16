package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DesktopHardwareTest {

    private val never: () -> Boolean = { fail("a wallet with envelopes or enclave state must not ask the enclave") }

    @Test
    fun aNewWalletUsesTheEnclaveWhenItWorks() {
        assertEquals(DesktopFactor.SECURE_ENCLAVE, chooseDesktopFactor(false, emptySet()) { true })
        assertEquals(DesktopFactor.PASSWORD_ONLY, chooseDesktopFactor(false, emptySet()) { false })
    }

    @Test
    fun aWalletOnTheEnclaveStaysOnItEvenWhenItFails() {
        assertEquals(DesktopFactor.SECURE_ENCLAVE, chooseDesktopFactor(true, emptySet(), never))
        assertEquals(DesktopFactor.SECURE_ENCLAVE, chooseDesktopFactor(false, setOf(MacSecureEnclaveSecret.TAG), never))
    }

    @Test
    fun aWalletBegunOnThePasswordAloneStaysSo() {
        assertEquals(DesktopFactor.PASSWORD_ONLY, chooseDesktopFactor(false, setOf("none"), never))
    }
}
