package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnusable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleBiometricsTest {

    private val made = BiometricKeyState(version = 1, key = "00", enrolled = byteArrayOf(1, 2, 3).toHex())

    @Test
    fun aDismissedPromptIsACancelWhoeverDismissedIt() {
        listOf(LaError.USER_CANCEL, LaError.USER_FALLBACK, LaError.SYSTEM_CANCEL, LaError.APP_CANCEL).forEach {
            assertEquals(BiometricUnwrap.Cancelled, LaError.unwrapFailure(it), "code $it")
        }
    }

    @Test
    fun aFingerNotRecognisedIsAFailureNotACancel() {
        assertEquals(BiometricUnwrap.Failed, LaError.unwrapFailure(LaError.AUTHENTICATION_FAILED))
        assertEquals(BiometricUnwrap.Failed, LaError.unwrapFailure(-99L), "an unknown code fails too")
    }

    @Test
    fun whatNoBiometricCanOpenNowSaysWhy() {
        assertEquals(
            BiometricUnwrap.Unusable(BiometricUnusable.LOCKED_OUT),
            LaError.unwrapFailure(LaError.BIOMETRY_LOCKOUT),
        )
        assertEquals(
            BiometricUnwrap.Unusable(BiometricUnusable.NO_PROMPT),
            LaError.unwrapFailure(LaError.NOT_INTERACTIVE),
        )
        // With no finger or passcode left, the key made under them opens nothing any more.
        listOf(LaError.BIOMETRY_NOT_ENROLLED, LaError.PASSCODE_NOT_SET).forEach {
            assertEquals(BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED), LaError.unwrapFailure(it))
        }
        // A Mac's Touch ID keyboard unplugged, or the lid closed: the sensor, not the key.
        listOf(LaError.BIOMETRY_NOT_AVAILABLE, LaError.BIOMETRY_NOT_PAIRED, LaError.BIOMETRY_DISCONNECTED).forEach {
            assertEquals(BiometricUnwrap.Unusable(BiometricUnusable.SENSOR_UNAVAILABLE), LaError.unwrapFailure(it))
        }
    }

    @Test
    fun theEnrolledSetIsReadOnlyWhereItCanBe() {
        val known = LaError.enrolled(canEvaluate = null) { byteArrayOf(1, 2, 3) }
        assertIs<Enrolled.Known>(known)
        // Locked out, the set can still be read: it is the passcode that is wanted, not a change.
        assertIs<Enrolled.Known>(LaError.enrolled(LaError.BIOMETRY_LOCKOUT) { byteArrayOf(1, 2, 3) })
        assertEquals(Enrolled.Unknown, LaError.enrolled(canEvaluate = null) { null })
        assertEquals(Enrolled.None, LaError.enrolled(LaError.BIOMETRY_NOT_ENROLLED) { error("not read") })
        assertEquals(Enrolled.None, LaError.enrolled(LaError.PASSCODE_NOT_SET) { error("not read") })
        assertEquals(Enrolled.Unknown, LaError.enrolled(LaError.BIOMETRY_NOT_AVAILABLE) { error("not read") })
    }

    @Test
    fun aChangedOrEmptiedSetInvalidatesTheKeyAnUnknownOneDoesNot() {
        assertFalse(Enrolled.Known(byteArrayOf(1, 2, 3)).changedSince(made))
        assertTrue(Enrolled.Known(byteArrayOf(1, 2, 4)).changedSince(made), "a finger added or removed")
        assertTrue(Enrolled.None.changedSince(made), "none left")
        // The enclave refuses an invalidated key anyway: not knowing is no reason to say so.
        assertFalse(Enrolled.Unknown.changedSince(made))
    }
}
