package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class IosHardwareTest {

    private val never: () -> Boolean = { fail("only a new wallet on the simulator asks the enclave") }

    @Test
    fun aNewWalletOnADeviceUsesTheEnclaveWithoutAsking() {
        // Not asked: a locked device's enclave would refuse, and there is no other factor anyway.
        assertEquals(IosFactor.SECURE_ENCLAVE, chooseIosFactor(false, false, emptySet(), never))
    }

    @Test
    fun aNewWalletOnTheSimulatorUsesTheEnclaveOnlyWhenItWorks() {
        assertEquals(IosFactor.SECURE_ENCLAVE, chooseIosFactor(true, false, emptySet()) { true })
        assertEquals(IosFactor.PASSWORD_ONLY, chooseIosFactor(true, false, emptySet()) { false })
    }

    @Test
    fun aWalletOnTheEnclaveStaysOnItEvenWhenItFails() {
        for (onSimulator in listOf(false, true)) {
            assertEquals(IosFactor.SECURE_ENCLAVE, chooseIosFactor(onSimulator, true, emptySet(), never))
            assertEquals(
                IosFactor.SECURE_ENCLAVE,
                chooseIosFactor(onSimulator, false, setOf(IosSecureEnclaveSecret.TAG), never),
            )
        }
    }

    @Test
    fun aPasswordOnlyWalletOpensOnlyOnTheSimulator() {
        assertEquals(IosFactor.PASSWORD_ONLY, chooseIosFactor(true, false, setOf("none"), never))
        assertEquals(IosFactor.UNAVAILABLE, chooseIosFactor(false, false, setOf("none"), never))
    }
}
