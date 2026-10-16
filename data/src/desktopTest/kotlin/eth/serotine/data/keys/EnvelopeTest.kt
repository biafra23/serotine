package eth.serotine.data.keys

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvelopeTest {

    private val seed = ByteArray(64) { (it * 7).toByte() }
    private val address = "0x" + "ab".repeat(20)
    private val cost = Envelope.Cost(opsLimit = 2, memLimitBytes = Envelope.MIN_MEMORY_BYTES)

    private fun pw(text: String) = text.encodeToByteArray()

    @Test
    fun opensWithThePasswordAndTheHardware() {
        val hardware = FakeHardware()
        val sealed = Envelope.seal(seed, address, pw("correct horse"), hardware, cost)

        val opened = assertIs<Envelope.Opened.Seed>(Envelope.open(sealed, pw("correct horse"), hardware))
        assertContentEquals(seed, opened.bytes)
    }

    @Test
    fun theWrongPasswordDoesNotOpenIt() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)

        assertEquals(Envelope.Opened.WrongPassword, Envelope.open(sealed, pw("correct hors"), null))
    }

    @Test
    fun bothFactorsAreNeeded() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), FakeHardware(key = 1), cost)

        // Another device's secret (same tag, other key): the password alone is not enough.
        assertEquals(Envelope.Opened.WrongPassword, Envelope.open(sealed, pw("correct horse"), FakeHardware(key = 2)))
        // No hardware where the envelope names one: refused, not tried.
        assertEquals(Envelope.Opened.Refused, Envelope.open(sealed, pw("correct horse"), null))
    }

    @Test
    fun aParameterBlockEditedDownwardsIsRefused() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)
        val weakened = SealedSeed(
            sealed.version, sealed.address, sealed.hardware, sealed.salt,
            opsLimit = 1, memLimitBytes = sealed.memLimitBytes,
            sealed.dekNonce, sealed.dek, sealed.seedNonce, sealed.seed,
        )

        assertEquals(Envelope.Opened.Refused, Envelope.open(weakened, pw("correct horse"), null))
    }

    @Test
    fun theParametersAreBoundSoChangingThemBreaksTheWrap() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)
        // Raised, not lowered: past the floor check, but no longer what the wrap was made with.
        val edited = SealedSeed(
            sealed.version, sealed.address, sealed.hardware, sealed.salt,
            opsLimit = 3, memLimitBytes = sealed.memLimitBytes,
            sealed.dekNonce, sealed.dek, sealed.seedNonce, sealed.seed,
        )

        assertEquals(Envelope.Opened.WrongPassword, Envelope.open(edited, pw("correct horse"), null))
    }

    @Test
    fun verifyChecksThePasswordWithoutOpeningTheSeed() {
        val hardware = FakeHardware()
        val sealed = Envelope.seal(seed, address, pw("correct horse"), hardware, cost)

        assertEquals(Envelope.Opened.Verified, Envelope.verify(sealed, pw("correct horse"), hardware))
        assertEquals(Envelope.Opened.WrongPassword, Envelope.verify(sealed, pw("correct hors"), hardware))
    }

    @Test
    fun theAddressAndTheHardwareTagAreBoundUnderAHardwareSecret() {
        val hardware = FakeHardware()
        val sealed = Envelope.seal(seed, address, pw("correct horse"), hardware, cost)
        val otherAddress = copy(sealed, address = "0x" + "cd".repeat(20))
        val otherTag = copy(sealed, hardware = "other-hw-v1")

        assertEquals(Envelope.Opened.WrongPassword, Envelope.open(otherAddress, pw("correct horse"), hardware))
        // Under another device's tag: not tried with this device's secret at all.
        assertEquals(Envelope.Opened.Refused, Envelope.open(otherTag, pw("correct horse"), hardware))
        assertFalse(Envelope.canOpen(sealed, hardware, address = "0x" + "cd".repeat(20)), "the file's own name")
    }

    @Test
    fun parametersAboveTheCeilingOrAMisshapenSaltAreRefusedUntried() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)

        for (edited in listOf(
            copy(sealed, opsLimit = Envelope.MAX_OPS + 1),
            copy(sealed, memLimitBytes = Envelope.MAX_MEMORY_BYTES * 16),
            copy(sealed, salt = sealed.salt.take(8)),
            copy(sealed, salt = ""),
            copy(sealed, dekNonce = "zz" + sealed.dekNonce.drop(2)),
        )) {
            assertFalse(Envelope.canOpen(edited, null))
            assertEquals(Envelope.Opened.Refused, Envelope.open(edited, pw("correct horse"), null))
        }
        assertFailsWith<IllegalArgumentException> { Envelope.Cost(opsLimit = Envelope.MAX_OPS + 1, cost.memLimitBytes) }
    }

    @Test
    fun theStoredFormHoldsNoSeed() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)

        assertTrue(seed.toHex() !in sealed.seed && seed.toHex() !in sealed.dek)
        assertEquals(64 + 16, sealed.seed.length / 2, "ciphertext plus the GCM tag")
    }

    private fun copy(
        sealed: SealedSeed,
        address: String = sealed.address,
        hardware: String = sealed.hardware,
        salt: String = sealed.salt,
        opsLimit: Long = sealed.opsLimit,
        memLimitBytes: Long = sealed.memLimitBytes,
        dekNonce: String = sealed.dekNonce,
    ) = SealedSeed(
        sealed.version, address, hardware, salt, opsLimit, memLimitBytes,
        dekNonce, sealed.dek, sealed.seedNonce, sealed.seed,
    )
}
