package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What [IosBiometricKey] does where these tests run, the simulator: it has no Secure Enclave, so
 * the path is not offered (rule 5) and nothing is made. The key itself needs a device.
 */
class IosBiometricKeyTest {

    private val stateFile = Path(NSTemporaryDirectory(), "biometric-${NSUUID().UUIDString}", "biometric-key.json")
    private val key = IosBiometricKey(stateFile.toString())

    @Test
    fun theSimulatorOffersNoBiometricPath() {
        if (!onIosSimulator) return
        assertEquals(BiometricUnavailable.NOT_IN_SECURE_HARDWARE, key.unavailable())
        assertFalse(key.create())
        assertFalse(key.exists())
        assertFalse(SystemFileSystem.exists(stateFile))
    }

    @Test
    fun noKeyIsOffAndNotInvalidated() = runTest {
        assertFalse(key.exists())
        assertFalse(key.isInvalidated())
        assertEquals(
            BiometricUnwrap.Unusable(BiometricUnusable.OFF),
            key.unwrap(ByteArray(16), "a reason"),
        )
        key.delete() // nothing to delete: no failure
    }
}
