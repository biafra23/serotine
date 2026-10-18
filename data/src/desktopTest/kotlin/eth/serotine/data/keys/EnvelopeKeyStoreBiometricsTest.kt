package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.BiometricUnusable
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The envelope's biometric path (docs/keys-and-signing.md, rules 3 and 4), over a fake biometric key. */
class EnvelopeKeyStoreBiometricsTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")
    private val password = WalletPassword("correct horse battery")
    private val hardhatAddress = Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val abandonAddress = Address.of("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
    private val digest = Digest.of("0x" + "5a".repeat(32))
    private val biometrics = Unlock.WithBiometrics("Send 0.1 ETH to 0x5aAe…eAed")
    private val key = FakeBiometricKey()

    private companion object {
        const val CONFIRM = "Turn on biometric unlock"
    }

    private fun TestScope.store() =
        EnvelopeKeyStore(directory.path, hardware = null, StandardTestDispatcher(testScheduler), biometricKey = key)

    private fun wrapOf(address: Address) = directory.resolve("${address.hex}.bio")

    private suspend fun EnvelopeKeyStore.biometrics() = (describe() as KeyStoreDescription.Ready).biometrics

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun withoutABiometricKeyThePlatformHasNone() = runTest {
        val store = EnvelopeKeyStore(directory.path, hardware = null, StandardTestDispatcher(testScheduler))
        store.importMnemonic(hardhat, password)

        assertEquals(BiometricUnlock.NotOnThisDevice, (store.describe() as KeyStoreDescription.Ready).biometrics)
        assertEquals(BiometricChange.NotOnThisDevice, store.enableBiometrics(password, CONFIRM))
        assertEquals(
            Signing.BiometricsUnusable(BiometricUnusable.OFF),
            store.sign(hardhatAddress, digest, biometrics),
        )
    }

    @Test
    fun turnedOnWithThePasswordItSignsAsThePasswordDoes() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)
        assertEquals(BiometricUnlock.Off, store.biometrics())

        assertEquals(BiometricChange.Enabled, store.enableBiometrics(password, CONFIRM))

        assertEquals(BiometricUnlock.On, store.biometrics())
        assertTrue(wrapOf(hardhatAddress).exists() && wrapOf(abandonAddress).exists(), "every key wrapped")
        val byPassword = store.sign(hardhatAddress, digest, Unlock.WithPassword(password))
        val byBiometric = store.sign(hardhatAddress, digest, biometrics)
        assertIs<Signing.Signed>(byBiometric)
        assertEquals(byPassword, byBiometric, "the same key, the same deterministic signature")
        // Proven once when turned on, then the prompt says what is signed.
        assertEquals(listOf(CONFIRM, biometrics.reason), key.reasons)
        assertIs<Signing.Signed>(store.sign(abandonAddress, digest, biometrics))
    }

    @Test
    fun aPromptNotAnsweredWithinTheDeadlineSignsNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        assertEquals(BiometricChange.Enabled, store.enableBiometrics(password, CONFIRM))
        // The test's virtual clock: the key store's timeout runs on it too.
        val clock = testScheduler.timeSource
        val within = Unlock.WithBiometrics("Send 0.1 ETH to 0x5aAe…eAed", notAfter = clock.markNow() + 30.seconds)

        // Answered in time: signed.
        assertIs<Signing.Signed>(store.sign(hardhatAddress, digest, within))

        // Not answered: past the deadline the prompt is dismissed, and nothing is signed.
        key.gate = CompletableDeferred()
        val late = async { store.sign(hardhatAddress, digest, within) }
        advanceTimeBy(29.seconds)
        runCurrent()
        assertFalse(late.isCompleted, "the prompt is still up")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(Signing.TimedOut, late.await())
        assertTrue(key.gate?.isCompleted == false, "never answered: the prompt was cancelled, not passed")

        // A deadline already gone asks nothing at all.
        key.gate = null
        val asked = key.reasons.size
        val gone = within.copy(notAfter = clock.markNow())
        assertEquals(Signing.TimedOut, store.sign(hardhatAddress, digest, gone))
        assertEquals(asked, key.reasons.size)
    }

    @Test
    fun aWrongPasswordTurnsNothingOn() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(BiometricChange.WrongPassword, store.enableBiometrics(WalletPassword("not the password"), CONFIRM))

        assertEquals(BiometricUnlock.Off, store.biometrics())
        assertEquals(0, key.made)
        assertFalse(wrapOf(hardhatAddress).exists())
    }

    @Test
    fun withNoKeyStoredThereIsNothingToTurnOn() = runTest {
        assertEquals(BiometricChange.NoKeys, store().enableBiometrics(password, CONFIRM))
    }

    @Test
    fun aDeviceThatCannotOfferItSaysWhy() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.unavailable = BiometricUnavailable.WEAK_ONLY

        assertEquals(BiometricUnlock.Unavailable(BiometricUnavailable.WEAK_ONLY), store.biometrics())
        assertEquals(
            BiometricChange.Unavailable(BiometricUnavailable.WEAK_ONLY),
            store.enableBiometrics(password, CONFIRM),
        )

        key.unavailable = null
        key.inSecureHardware = false
        assertEquals(
            BiometricChange.Unavailable(BiometricUnavailable.NOT_IN_SECURE_HARDWARE),
            store.enableBiometrics(password, CONFIRM),
        )
        assertFalse(wrapOf(hardhatAddress).exists(), "nothing is wrapped by a key the device refused")
        assertEquals(BiometricUnlock.Off, store.biometrics())
    }

    @Test
    fun aKeyStoredWhileItIsOnJoinsIt() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)

        store.importMnemonic(abandon, password)

        assertTrue(wrapOf(abandonAddress).exists())
        assertIs<Signing.Signed>(store.sign(abandonAddress, digest, biometrics))
    }

    @Test
    fun turnedOffItsKeyAndWrapsAreGoneAndThePasswordStillSigns() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)

        assertEquals(BiometricChange.Disabled, store.disableBiometrics())

        assertFalse(key.exists())
        assertFalse(wrapOf(hardhatAddress).exists())
        assertEquals(BiometricUnlock.Off, store.biometrics())
        assertEquals(Signing.BiometricsUnusable(BiometricUnusable.OFF), store.sign(hardhatAddress, digest, biometrics))
        assertIs<Signing.Signed>(store.sign(hardhatAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aDismissedPromptSignsNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)
        key.prompt = BiometricUnwrap.Cancelled

        assertEquals(Signing.Cancelled, store.sign(hardhatAddress, digest, biometrics))
    }

    @Test
    fun aNewEnrolmentInvalidatesThePathUntilItIsTurnedOnAgain() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)
        key.invalidated = true

        assertEquals(BiometricUnlock.Invalidated, store.biometrics())
        assertEquals(
            Signing.BiometricsUnusable(BiometricUnusable.INVALIDATED),
            store.sign(hardhatAddress, digest, biometrics),
        )

        assertEquals(BiometricChange.Enabled, store.enableBiometrics(password, CONFIRM))
        assertEquals(BiometricUnlock.On, store.biometrics())
        assertIs<Signing.Signed>(store.sign(hardhatAddress, digest, biometrics))
    }

    @Test
    fun aWrapFiledUnderAnotherAccountOpensNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)
        store.enableBiometrics(password, CONFIRM)
        // hardhat's DEK filed as abandon's: it does not open abandon's seed, whose tag binds its address.
        val misfiled = wrapOf(hardhatAddress).readText().replace(hardhatAddress.hex, abandonAddress.hex)
        wrapOf(abandonAddress).writeText(misfiled)

        assertEquals(Signing.Unavailable, store.sign(abandonAddress, digest, biometrics))
    }

    @Test
    fun aWrapThatFailsTurnsNothingOn() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.failWrap = true

        assertEquals(BiometricChange.Failed, store.enableBiometrics(password, CONFIRM))

        assertFalse(key.exists(), "no key without its wraps")
        assertFalse(wrapOf(hardhatAddress).exists())
        assertEquals(BiometricUnlock.Off, store.biometrics())
    }

    @Test
    fun anImportWhoseWrapFailsIsStoredOnThePasswordAlone() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)
        key.failWrap = true

        assertIs<KeyImport.Stored>(store.importMnemonic(abandon, password))

        assertFalse(wrapOf(abandonAddress).exists())
        assertEquals(Signing.BiometricsUnusable(BiometricUnusable.OFF), store.sign(abandonAddress, digest, biometrics))
        assertIs<Signing.Signed>(store.sign(abandonAddress, digest, Unlock.WithPassword(password)))
    }

    @Test
    fun aNewEnvelopeNeverInheritsAnEarlierOnesWrap() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)
        store.enableBiometrics(password, CONFIRM)
        val stale = wrapOf(abandonAddress).readText()
        store.delete(abandonAddress)
        // Its wrap left behind (a deletion that failed), then the account imported again.
        wrapOf(abandonAddress).writeText(stale)
        key.failWrap = true

        store.importMnemonic(abandon, password)

        assertFalse(wrapOf(abandonAddress).exists(), "the old DEK's wrap is gone with its envelope")
        assertEquals(Signing.BiometricsUnusable(BiometricUnusable.OFF), store.sign(abandonAddress, digest, biometrics))
    }

    @Test
    fun aBiometricKeyThatFailsNeverTakesThePasswordPathDown() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.failDescribe = true

        assertEquals(
            KeyStoreDescription.Ready(
                passwordSet = true,
                KeyProtection.PASSWORD_ONLY,
                BiometricUnlock.Unavailable(BiometricUnavailable.NO_HARDWARE),
            ),
            store.describe(),
        )
    }

    @Test
    fun aDismissedProofTurnsNothingOn() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.prompt = BiometricUnwrap.Cancelled

        assertEquals(BiometricChange.Cancelled, store.enableBiometrics(password, CONFIRM))

        assertFalse(key.exists())
        assertFalse(wrapOf(hardhatAddress).exists())
        assertEquals(listOf(CONFIRM), key.reasons, "the prompt said what it was for")
    }

    @Test
    fun aProofTheSensorCannotGiveTurnsNothingOn() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.prompt = BiometricUnwrap.Unusable(BiometricUnusable.SENSOR_UNAVAILABLE)

        assertEquals(BiometricChange.NotConfirmed, store.enableBiometrics(password, CONFIRM))
        assertEquals(BiometricUnlock.Off, store.biometrics())
    }

    @Test
    fun anEnableCancelledDuringItsProofLeavesThePathOff() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        key.gate = CompletableDeferred()

        val enabling = launch { store.enableBiometrics(password, CONFIRM) }
        runCurrent()
        assertTrue(key.exists(), "made, waiting for its proof")
        enabling.cancel()
        runCurrent()

        assertFalse(key.exists())
        assertFalse(wrapOf(hardhatAddress).exists())
    }

    @Test
    fun whileItsKeyExistsThePathIsOnAndCanBeTurnedOffEvenWithTheSensorUnusable() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)
        key.unavailable = BiometricUnavailable.NO_HARDWARE

        assertEquals(BiometricUnlock.On, store.biometrics())
        assertEquals(BiometricChange.Disabled, store.disableBiometrics())
        assertEquals(BiometricUnlock.Unavailable(BiometricUnavailable.NO_HARDWARE), store.biometrics())
    }

    @Test
    fun aSoftwareKeyStoreIsToldBeforeThePasswordIsAsked() = runTest {
        val store = EnvelopeKeyStore(
            directory.path,
            FakeHardware(claims = { KeyProtection.SOFTWARE_KEY_STORE }),
            StandardTestDispatcher(testScheduler),
            biometricKey = key,
        )
        store.importMnemonic(hardhat, password)

        assertEquals(BiometricUnlock.Unavailable(BiometricUnavailable.NOT_IN_SECURE_HARDWARE), store.biometrics())
        assertEquals(
            BiometricChange.Unavailable(BiometricUnavailable.NOT_IN_SECURE_HARDWARE),
            store.enableBiometrics(password, CONFIRM),
        )
        assertEquals(0, key.made, "no key made, no password asked of the envelopes")
    }

    @Test
    fun aBiometricKeyThatCannotBeAskedNeverFailsAnImport() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)
        key.failQueries = true

        assertIs<KeyImport.Stored>(store.importMnemonic(abandon, password))
        assertFalse(wrapOf(abandonAddress).exists())
    }

    @Test
    fun aDamagedEnvelopeAmongOthersGetsNoWrapAndTheRestDo() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)
        // A flipped digit in hardhat's wrapped DEK: well-formed, so only its tag refuses.
        val file = directory.resolve("${hardhatAddress.hex}.seed")
        val dek = Regex("\"dek\":\"([0-9a-f])").find(file.readText())!!.groupValues[1]
        file.writeText(file.readText().replace("\"dek\":\"$dek", "\"dek\":\"${if (dek == "0") "1" else "0"}"))

        assertEquals(BiometricChange.Enabled, store.enableBiometrics(password, CONFIRM))

        assertFalse(wrapOf(hardhatAddress).exists())
        assertTrue(wrapOf(abandonAddress).exists())
        assertEquals(Signing.BiometricsUnusable(BiometricUnusable.OFF), store.sign(hardhatAddress, digest, biometrics))
    }

    @Test
    fun withTheLastKeyGoesThePath() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.enableBiometrics(password, CONFIRM)

        assertEquals(KeyDeletion.Deleted, store.delete(hardhatAddress))

        assertFalse(key.exists(), "the next wallet starts without it")
        assertFalse(wrapOf(hardhatAddress).exists())
        assertEquals(
            KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY, BiometricUnlock.Off),
            store.describe(),
        )
    }

    @Test
    fun turningOnShortOfMemoryLeavesThePathOffAndSaysWhy() = runTest {
        // Ruling #36: Argon2id cannot have its memory while the envelopes are opened.
        var memoryAvailable = Long.MAX_VALUE
        val stretch = PasswordStretch { pw, salt, ops, mem, length ->
            if (mem > memoryAvailable) throw Argon2idOutOfMemory()
            Hkdf.sha256(pw, salt, "$ops|$mem".encodeToByteArray(), length)
        }
        val store = EnvelopeKeyStore(
            directory.path,
            hardware = null,
            StandardTestDispatcher(testScheduler),
            KeyProtection.PASSWORD_ONLY,
            key,
            PasswordOnlyTier.FULL,
            stretch,
        )
        store.importMnemonic(hardhat, password)
        memoryAvailable = 0

        assertEquals(BiometricChange.NotEnoughMemory, store.enableBiometrics(password, CONFIRM))
        assertEquals(0, key.made, "no biometric key is made")
        assertEquals(BiometricUnlock.Off, store.biometrics())
        assertFalse(wrapOf(hardhatAddress).exists())
    }
}
