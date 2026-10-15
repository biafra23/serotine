package eth.serotine.data.keys

import eth.serotine.domain.repository.KeyProtection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvelopeTest {

    private val seed = ByteArray(64) { (it * 7).toByte() }
    private val address = "0x" + "ab".repeat(20)
    private val cost = Envelope.Cost(opsLimit = 2, memLimitBytes = Envelope.MIN_MEMORY_BYTES)

    private class FakeHardware(override val tag: String = "fake-hw-v1", private val key: Byte = 42) : HardwareSecret {
        override val protection = KeyProtection.HARDWARE
        override fun mix(input: ByteArray) = ByteArray(32) { (input[it % input.size].toInt() xor key.toInt()).toByte() }
    }

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
    fun theStoredFormHoldsNoSeed() {
        val sealed = Envelope.seal(seed, address, pw("correct horse"), null, cost)

        assertTrue(seed.toHex() !in sealed.seed && seed.toHex() !in sealed.dek)
        assertEquals(64 + 16, sealed.seed.length / 2, "ciphertext plus the GCM tag")
    }
}
