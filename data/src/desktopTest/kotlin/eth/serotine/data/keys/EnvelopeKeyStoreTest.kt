package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvelopeKeyStoreTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")
    private val password = WalletPassword("correct horse battery")

    private val hardhatAddress = Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val abandonAddress = Address.of("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")

    private fun TestScope.store(hardware: HardwareSecret? = null) =
        EnvelopeKeyStore(directory.path, hardware, StandardTestDispatcher(testScheduler))

    private fun passwordOnly(address: Address) = KeyImport.Stored(address, KeyProtection.PASSWORD_ONLY)

    private fun envelopeOf(address: Address): File = directory.resolve("${address.hex}.seed")

    private fun sealedIn(file: File): SealedSeed = Json.decodeFromString(SealedSeed.serializer(), file.readText())

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun storesTheSeedAndNamesTheFirstAccountOtherWalletsShow() = runTest {
        val store = store()
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.PASSWORD_ONLY), store.describe())
        assertEquals(
            listOf("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266.seed"),
            directory.list()!!.toList(),
            "one envelope, no temporary left behind",
        )
    }

    @Test
    fun deletingAKeyRemovesItsEnvelopeAndWithTheLastOneThePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        store.importMnemonic(abandon, password)

        assertEquals(KeyDeletion.Deleted, store.delete(hardhatAddress))
        assertFalse(envelopeOf(hardhatAddress).exists())
        assertEquals(KeyDeletion.NotFound, store.delete(hardhatAddress))
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.PASSWORD_ONLY), store.describe())

        assertEquals(KeyDeletion.Deleted, store.delete(abandonAddress))
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
        // No envelope left to check a password against: the next import sets a new one.
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, WalletPassword("another password")))
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedAndStaysStored() = runTest {
        val store = store()
        // What the file system refuses to delete: a non-empty directory under the envelope's name.
        envelopeOf(hardhatAddress).mkdirs()
        envelopeOf(hardhatAddress).resolve("in-the-way").writeText("x")

        assertEquals(KeyDeletion.Failed, store.delete(hardhatAddress))
        assertTrue(envelopeOf(hardhatAddress).exists())
    }

    @Test
    fun laterImportsMustUseTheSamePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("something else")))
        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("short")))
        assertEquals(1, directory.list()!!.size, "nothing stored on a wrong password")
        assertEquals(passwordOnly(abandonAddress), store.importMnemonic(abandon, password))
    }

    @Test
    fun aTooShortFirstPasswordIsRefused() = runTest {
        val store = store()

        assertEquals(KeyImport.PasswordTooShort, store.importMnemonic(hardhat, WalletPassword("short")))
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
    }

    @Test
    fun theStoredEnvelopeOpensWithThePasswordAndHoldsTheSeed() = runTest {
        store().importMnemonic(hardhat, password)
        val file = directory.listFiles()!!.single()
        val sealed = sealedIn(file)

        val opened = Envelope.open(sealed, password.text.encodeToByteArray(), null) as Envelope.Opened.Seed
        assertEquals(Bip39.seed(hardhat.words).toHex(), opened.bytes.toHex())
        // Nothing readable on disk: not the words, not the seed.
        assertFalse("junk" in file.readText())
        assertFalse(Bip39.seed(hardhat.words).toHex() in file.readText())
        // Password-only: the stronger KDF cost of rule 9.
        assertEquals(256L * 1024 * 1024, sealed.memLimitBytes)
    }

    @Test
    fun theLighterCostIsOnlyForAKeyInSecureHardware() = runTest {
        store(FakeHardware(claims = { KeyProtection.HARDWARE })).importMnemonic(hardhat, password)
        val inHardware = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(3L to 64L * 1024 * 1024, inHardware.opsLimit to inHardware.memLimitBytes)

        // An emulator's software key store: a copy of the device's storage yields its key.
        assertEquals(
            KeyImport.Stored(abandonAddress, KeyProtection.SOFTWARE_KEY_STORE),
            store(FakeHardware(claims = { KeyProtection.SOFTWARE_KEY_STORE })).importMnemonic(abandon, password),
        )
        val inSoftware = sealedIn(envelopeOf(abandonAddress))
        assertEquals(4L to 256L * 1024 * 1024, inSoftware.opsLimit to inSoftware.memLimitBytes)
    }

    @Test
    fun aLostHardwareKeyMakesTheKeyStoreUnavailableNotAWrongPassword() = runTest {
        store(FakeHardware()).importMnemonic(hardhat, password)
        // The Keystore key is gone (an OS upgrade, a cleared key store); the envelope stays.
        var provisioned = false
        val lost = store(FakeHardware(provisioned = { provisioned }))

        assertEquals(KeyStoreDescription.Unavailable, lost.describe())
        assertEquals(KeyImport.Unavailable, lost.importMnemonic(abandon, password))
        assertEquals(listOf("${hardhatAddress.hex}.seed"), directory.list()!!.toList(), "nothing sealed beside it")

        provisioned = true
        assertEquals(KeyStoreDescription.Ready(passwordSet = true, KeyProtection.HARDWARE), lost.describe())
    }

    @Test
    fun aNewWalletNeedsNoHardwareKeyYet() = runTest {
        val store = store(FakeHardware(provisioned = { false }))

        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.HARDWARE), store.describe())
    }

    @Test
    fun aMistypedPasswordIsCheckedAgainstAtMostThreeEnvelopes() = runTest {
        var mixes = 0
        val hardware = FakeHardware(mixed = { mixes++ })
        val store = store(hardware)
        val phrases = listOf(
            hardhat,
            abandon,
            Mnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow"),
            Mnemonic("letter advice cage absurd amount doctor acoustic avoid letter advice cage above"),
            Mnemonic("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"),
        )
        phrases.dropLast(1).forEach { assertIs<KeyImport.Stored>(store.importMnemonic(it, password)) }
        mixes = 0

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(phrases.last(), WalletPassword("mistyped password")))
        assertEquals(3, mixes, "one Argon2id run per envelope asked, and no more than three")
    }

    @Test
    fun aComputerWhoseTpmIsNotUsedYetSaysSo() = runTest {
        // Windows and Linux, for now (the owner's ruling of 2026-10-16).
        val store = EnvelopeKeyStore(
            directory.path,
            hardware = null,
            StandardTestDispatcher(testScheduler),
            withoutHardware = KeyProtection.PASSWORD_ONLY_TPM_NOT_USED,
        )

        assertEquals(
            KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED),
            store.describe(),
        )
        assertEquals(
            KeyImport.Stored(hardhatAddress, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED),
            store.importMnemonic(hardhat, password),
        )
        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(4L to 256L * 1024 * 1024, sealed.opsLimit to sealed.memLimitBytes, "the password-only cost")
        assertFailsWith<IllegalArgumentException>("a hardware kind is no password-only protection") {
            EnvelopeKeyStore(directory.path, hardware = null, withoutHardware = KeyProtection.HARDWARE)
        }
    }

    @Test
    fun aStoredSecretTheHardwareUnwrapsGetsTheFullCostToo() = runTest {
        // The Secure Enclave's kind: in hardware, but one unwrap and the search can leave the device.
        store(FakeHardware(everyGuessNeedsDevice = false)).importMnemonic(hardhat, password)

        val sealed = sealedIn(envelopeOf(hardhatAddress))
        assertEquals(4L to 256L * 1024 * 1024, sealed.opsLimit to sealed.memLimitBytes)
    }

    @Test
    fun filesThatCannotBeReadOrOpenedHereArePassedOver() = runTest {
        directory.resolve("0x" + "aa".repeat(20) + ".seed").writeText("{ not an envelope")
        val foreign = Envelope.seal(
            ByteArray(64),
            "0x" + "bb".repeat(20),
            "correct horse battery".encodeToByteArray(),
            FakeHardware(), // another device's secret: this store has none
            Envelope.Cost(opsLimit = 2, memLimitBytes = Envelope.MIN_MEMORY_BYTES),
        )
        directory.resolve("0x" + "bb".repeat(20) + ".seed")
            .writeText(Json.encodeToString(SealedSeed.serializer(), foreign))
        val store = store()

        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        // The one envelope this device can open decides the password from now on.
        assertEquals(KeyImport.WrongPassword, store.importMnemonic(abandon, WalletPassword("another password")))
    }

    @Test
    fun anEnvelopeUnderAnotherAccountsNameIsNotTrusted() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        // abandon's envelope, renamed to hardhat's: it opens, but it is not hardhat's seed.
        envelopeOf(abandonAddress).copyTo(envelopeOf(hardhatAddress))

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(hardhatAddress.hex, sealedIn(envelopeOf(hardhatAddress)).address, "sealed again, for hardhat")
    }

    @Test
    fun aDamagedEnvelopeIsSealedAgainOnTheNextImportOfItsWords() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        store.importMnemonic(hardhat, password)
        envelopeOf(hardhatAddress).writeText("damaged")

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(hardhat, WalletPassword("another password")))
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        val opened = Envelope.open(sealedIn(envelopeOf(hardhatAddress)), password.text.encodeToByteArray(), null)
        assertIs<Envelope.Opened.Seed>(opened)
    }

    @Test
    fun anEnvelopeDamagedPastParsingDoesNotLockOutTheRightPassword() = runTest {
        val store = store()
        store.importMnemonic(abandon, password)
        store.importMnemonic(hardhat, password)
        // A flipped digit in the wrapped DEK: still well-formed, so only the tag notices.
        val damaged = sealedIn(envelopeOf(hardhatAddress)).let { sealed ->
            val flipped = (if (sealed.dek[0] == '0') '1' else '0') + sealed.dek.drop(1)
            SealedSeed(
                sealed.version, sealed.address, sealed.hardware, sealed.salt, sealed.opsLimit, sealed.memLimitBytes,
                sealed.dekNonce, flipped, sealed.seedNonce, sealed.seed,
            )
        }
        envelopeOf(hardhatAddress).writeText(Json.encodeToString(SealedSeed.serializer(), damaged))

        assertEquals(KeyImport.WrongPassword, store.importMnemonic(hardhat, WalletPassword("another password")))
        // Its own envelope refuses, abandon's opens: the password is right, the file is damaged.
        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        val opened = Envelope.open(sealedIn(envelopeOf(hardhatAddress)), password.text.encodeToByteArray(), null)
        assertIs<Envelope.Opened.Seed>(opened, "sealed again")
    }

    @Test
    fun anImportAlreadyDoneWritesNothing() = runTest {
        val store = store()
        store.importMnemonic(hardhat, password)
        val before = envelopeOf(hardhatAddress).readText()

        assertEquals(passwordOnly(hardhatAddress), store.importMnemonic(hardhat, password))
        assertEquals(before, envelopeOf(hardhatAddress).readText())
    }

    @Test
    fun aCrashedWritesTemporaryIsSweptAway() = runTest {
        directory.resolve("0x" + "cc".repeat(20) + ".seed.0123456789ab.tmp").writeText("half an envelope")

        store().importMnemonic(hardhat, password)

        assertEquals(listOf("${hardhatAddress.hex}.seed"), directory.list()!!.toList())
    }

    @Test
    fun aCreateOnlyWriteNeverReplacesAFile() {
        val file = directory.resolve("state.json").apply { writeText("first") }

        assertFailsWith<FileAlreadyExistsException> {
            EnvelopeCrypto.writeDurably(file.path, "second".encodeToByteArray(), replace = false)
        }
        assertEquals("first", file.readText())
        assertEquals(listOf("state.json"), directory.list()!!.toList(), "no temporary left behind")
    }

    @Test
    fun theDirectoryAndTheEnvelopesAreThisUsersAlone() = runTest {
        // Owner permissions exist where the file system has them (not on Windows), as in EnvelopeCrypto.
        assumeTrue("no POSIX permissions here", "posix" in FileSystems.getDefault().supportedFileAttributeViews())
        store().importMnemonic(hardhat, password)

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.toPath())))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(envelopeOf(hardhatAddress).toPath())),
        )
    }

    @Test
    fun aFailingHardwareKeyStoreIsUnavailableNotACrash() = runTest {
        val store = store(FakeHardware(claims = { throw IllegalStateException("the secure element is gone") }))

        assertEquals(KeyStoreDescription.Unavailable, store.describe())
        assertEquals(KeyImport.Unavailable, store.importMnemonic(hardhat, password))
        assertTrue(directory.list()!!.isEmpty(), "nothing written")
    }

    @Test
    fun theSamePasswordComposedDifferentlyIsTheSamePassword() = runTest {
        val store = store()
        store.importMnemonic(hardhat, WalletPassword("pässwort und mehr")) // "ä" as one code point

        assertEquals(
            passwordOnly(abandonAddress),
            store.importMnemonic(abandon, WalletPassword("pässwort und mehr")), // "a" + combining diaeresis
        )
    }

    @Test
    fun wordsThatAreNotAMnemonicAreRefusedAndNothingIsStored() = runTest {
        val store = store()

        assertEquals(
            KeyImport.Invalid(MnemonicProblem.Checksum),
            store.importMnemonic(Mnemonic(List(12) { "test" }.joinToString(" ")), password),
        )
        assertEquals(KeyStoreDescription.Ready(passwordSet = false, KeyProtection.PASSWORD_ONLY), store.describe())
    }
}
