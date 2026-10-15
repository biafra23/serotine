package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvelopeKeyStoreTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")
    private val password = WalletPassword("correct horse battery")

    private fun TestScope.store() =
        EnvelopeKeyStore(directory.path, hardware = null, StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun storesTheSeedAndNamesTheFirstAccountOtherWalletsShow() = runTest {
        val store = store()
        assertFalse(store.hasPassword())

        assertEquals(
            KeyImport.Stored(Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"), KeyProtection.PASSWORD_ONLY),
            store.importMnemonic(hardhat, password),
        )
        assertTrue(store.hasPassword())
        assertEquals(
            listOf("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266.seed"),
            directory.list()!!.toList(),
            "one envelope, no temporary left behind",
        )
    }

    @Test
    fun laterImportsMustUseTheSamePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("something else")))
        assertEquals(1, directory.list()!!.size, "nothing stored on a wrong password")
        assertEquals(
            KeyImport.Stored(Address.of("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"), KeyProtection.PASSWORD_ONLY),
            store.importMnemonic(abandon, password),
        )
    }

    @Test
    fun theStoredEnvelopeOpensWithThePasswordAndHoldsTheSeed() = runTest {
        store().importMnemonic(hardhat, password)
        val file = directory.listFiles()!!.single()
        val sealed = kotlinx.serialization.json.Json.decodeFromString(SealedSeed.serializer(), file.readText())

        val opened = Envelope.open(sealed, password.text.encodeToByteArray(), null) as Envelope.Opened.Seed
        assertEquals(Bip39.seed(hardhat.words).toHex(), opened.bytes.toHex())
        // Nothing readable on disk: not the words, not the seed.
        assertFalse("junk" in file.readText())
        assertFalse(Bip39.seed(hardhat.words).toHex() in file.readText())
        // Password-only: the stronger KDF cost of rule 9.
        assertEquals(256L * 1024 * 1024, sealed.memLimitBytes)
    }

    @Test
    fun wordsThatAreNotAMnemonicAreRefusedAndNothingIsStored() = runTest {
        val store = store()

        assertEquals(
            KeyImport.Invalid(MnemonicProblem.Checksum),
            store.importMnemonic(Mnemonic(List(12) { "test" }.joinToString(" ")), password),
        )
        assertFalse(store.hasPassword())
    }
}
