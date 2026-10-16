package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Runs on a Mac whose Secure Enclave this process can use; skipped everywhere else (CI's Linux, Intel Macs). */
class MacSecureEnclaveSecretTest {

    private val directory: File = Files.createTempDirectory("serotine-enclave").toFile()
    private val stateFile = directory.resolve(MacSecureEnclaveSecret.STATE_FILE_NAME)

    private fun enclave() = MacSecureEnclaveSecret(stateFile.path)

    @BeforeTest
    fun onlyWhereTheEnclaveWorks() = assumeTrue("no usable Secure Enclave here", MacSecureEnclaveSecret.usable())

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun theSecretIsMadeOnceAndComesBackFromTheEnclave() {
        val first = MacSecureEnclaveSecret(stateFile.path).mix(ByteArray(32) { 1 })

        assertEquals(32, first.size)
        // Another instance on the same state file, another input: the enclave hands back the same secret.
        assertContentEquals(first, MacSecureEnclaveSecret(stateFile.path).mix(ByteArray(32) { 2 }))
        assertFalse(first.toHex() in stateFile.readText(), "only its encrypted form is stored")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(stateFile.toPath())))
    }

    @Test
    fun anEnvelopeOnTheEnclaveNeedsItsStateToOpen() = runTest {
        val store = EnvelopeKeyStore(directory.path, enclave(), StandardTestDispatcher(testScheduler))
        val hardhat = Mnemonic("test test test test test test test test test test test junk")
        val password = WalletPassword("correct horse battery")
        val address = Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")

        assertEquals(KeyImport.Stored(address, KeyProtection.HARDWARE), store.importMnemonic(hardhat, password))
        val envelope = directory.resolve("${address.hex}.seed")
        val sealed = Json.decodeFromString(SealedSeed.serializer(), envelope.readText())
        assertEquals(MacSecureEnclaveSecret.TAG, sealed.hardware)
        // A secret the enclave unwraps is the same for every guess: the full Argon2id cost.
        assertEquals(4L to 256L * 1024 * 1024, sealed.opsLimit to sealed.memLimitBytes)
        val opened = Envelope.open(sealed, password.text.encodeToByteArray(), MacSecureEnclaveSecret(stateFile.path))
        assertIs<Envelope.Opened.Seed>(opened)

        // Without its state, a new enclave secret is made, and it does not open the envelope.
        stateFile.delete()
        assertEquals(
            Envelope.Opened.WrongPassword,
            Envelope.open(sealed, password.text.encodeToByteArray(), MacSecureEnclaveSecret(stateFile.path)),
        )
    }

    @Test
    fun aDamagedStateMakesTheKeyStoreUnavailableNotPasswordOnly() = runTest {
        stateFile.writeText("""{"version":1,"key":"00","secret":"00"}""")
        val store = EnvelopeKeyStore(directory.path, enclave(), StandardTestDispatcher(testScheduler))

        val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")

        assertEquals(KeyImport.Unavailable, store.importMnemonic(abandon, WalletPassword("long enough")))
    }
}
