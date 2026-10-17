package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * [KeyStore] in the key adapter: each imported seed sealed in its own envelope ([Envelope]),
 * one file per account (`<address>.seed`) under [directory]. [hardware] is this device's
 * hardware secret, or null on a computer without one; [withoutHardware] is then what the key
 * store reports, [KeyProtection.PASSWORD_ONLY] or, on a computer whose TPM is not used yet,
 * [KeyProtection.PASSWORD_ONLY_TPM_NOT_USED]. Argon2id runs at the lighter cost only
 * when that secret really lives in secure hardware and every guess needs it; otherwise (a
 * software key store, a stored secret the hardware unwraps, none) the envelope must hold up
 * against an offline attack on the password alone (rules 2 and 9).
 *
 * The seed, the keys and the password's bytes exist only inside a call, as byte arrays this
 * code zeroes before it returns. Files are written durably and readable by this user alone
 * ([EnvelopeCrypto.writeDurably]); keeping [directory] out of backups and device transfers
 * (rule 8) is the host's part: Android's backup rules, the desktop's backup exclusion. A
 * file that cannot be read, or that this device must not open, is reported and passed over,
 * never trusted. Work runs on [dispatcher]; calls are serialized within the process.
 */
class EnvelopeKeyStore(
    private val directory: String,
    private val hardware: HardwareSecret?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val withoutHardware: KeyProtection = KeyProtection.PASSWORD_ONLY,
) : KeyStore, Signer {

    init {
        require(withoutHardware in PASSWORD_ONLY_KINDS) { "without hardware the protection is password-only" }
    }

    private val lock = Mutex()

    private fun protection(): KeyProtection = hardware?.protection ?: withoutHardware

    override suspend fun describe(): KeyStoreDescription = withContext(dispatcher) {
        try {
            if (!EnvelopeCrypto.available()) return@withContext KeyStoreDescription.Unavailable
            // The hardware first: when it fails, that is the one thing worth reporting. And
            // whether its key exists before anything could create it (protection may).
            val provisioned = hardware?.isProvisioned() ?: true
            val envelopes = envelopes()
            if (hardwareKeyLost(provisioned, envelopes)) return@withContext KeyStoreDescription.Unavailable
            val protection = protection()
            KeyStoreDescription.Ready(passwordSet = envelopes.isNotEmpty(), protection = protection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: the platform's key store is native code, which fails with Errors.
            report("the key store cannot be described (${e::class.simpleName})")
            KeyStoreDescription.Unavailable
        }
    }

    override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
        lock.withLock {
            withContext(dispatcher) {
                if (!EnvelopeCrypto.available()) return@withContext KeyImport.Unavailable
                val words = mnemonic.words
                Bip39.problemWith(words)?.let { return@withContext KeyImport.Invalid(it) }
                var passwordBytes = ByteArray(0)
                try {
                    passwordBytes = EnvelopeCrypto.passwordBytes(password.text)
                    import(words, password.isLongEnough, passwordBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable: libsodium and the platform's key store are native code, which
                    // fails with Errors. Nothing of the key path is shown.
                    report("the key store failed (${e::class.simpleName})")
                    KeyImport.Unavailable
                } finally {
                    passwordBytes.fill(0)
                }
            }
        }

    override suspend fun newMnemonic(): NewMnemonic = withContext(dispatcher) {
        if (!EnvelopeCrypto.available()) return@withContext NewMnemonic.Unavailable
        val entropy = EnvelopeCrypto.randomBytes(NEW_MNEMONIC_ENTROPY_BYTES)
        try {
            // The words leave the adapter under the mnemonic exception, as the one type they travel as.
            NewMnemonic.Created(Mnemonic(Bip39.wordsOf(entropy).joinToString(" ")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report("a new mnemonic could not be made (${e::class.simpleName})")
            NewMnemonic.Unavailable
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * Signs [digest] with the first account's key of the seed sealed under [account]'s name,
     * which must derive [account]: the seed, the key and the password's bytes exist only in
     * this call, in byte arrays zeroed before it returns (rule 7). secp256k1 runs in native code
     * (libsecp256k1); its signature has a low s, and the recovery id is found by recovering the
     * public key it names.
     */
    override suspend fun sign(account: Address, digest: Digest, password: WalletPassword): Signing =
        lock.withLock {
            withContext(dispatcher) {
                if (!EnvelopeCrypto.available()) return@withContext Signing.Unavailable
                var passwordBytes = ByteArray(0)
                try {
                    val file = Path(directory, "${account.hex}$SEED_SUFFIX")
                    if (!SystemFileSystem.exists(file)) return@withContext Signing.NoKey
                    val sealed = read(file) ?: return@withContext Signing.Unavailable
                    if (!Envelope.canOpen(sealed, hardware, account.hex)) return@withContext Signing.Unavailable
                    passwordBytes = EnvelopeCrypto.passwordBytes(password.text)
                    when (val opened = Envelope.open(sealed, passwordBytes, hardware)) {
                        Envelope.Opened.WrongPassword -> Signing.WrongPassword
                        Envelope.Opened.Refused, Envelope.Opened.Verified -> Signing.Unavailable
                        is Envelope.Opened.Seed -> signWith(opened.bytes, account, digest)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable: libsodium, libsecp256k1 and the key store are native code. Nothing
                    // of the key path is shown.
                    report("the signer failed (${e::class.simpleName})")
                    Signing.Unavailable
                } finally {
                    passwordBytes.fill(0)
                }
            }
        }

    /** [digest] signed by the first account's key of [seed], which must derive [account]; zeroes [seed]. */
    private fun signWith(seed: ByteArray, account: Address, digest: Digest): Signing {
        var key = ByteArray(0)
        val message = digest.hex.fromHex()
        try {
            key = Bip32.derive(seed, FIRST_ETHEREUM_ACCOUNT)
            // The envelope's name is the address it was sealed under; the key must be that account's.
            if (addressOf(key) != account) {
                report("the envelope of ${account.hex} does not hold its key")
                return Signing.Unavailable
            }
            val compact = Secp256k1.sign(message, key)
            val publicKey = Secp256k1.pubkeyCreate(key)
            val recoveryId = (0..1).firstOrNull { id ->
                Secp256k1.ecdsaRecover(compact, message, id).contentEquals(publicKey)
            } ?: return Signing.Unavailable
            return Signing.Signed(
                Signature(compact.copyOfRange(0, 32).toHex(), compact.copyOfRange(32, 64).toHex(), recoveryId),
            )
        } finally {
            key.fill(0)
            seed.fill(0)
        }
    }

    // A plain delete: the file system may keep the bytes until it reuses the blocks, which
    // leaves ciphertext that still needs the password and, on a device with one, the
    // hardware secret.
    override suspend fun delete(address: Address): KeyDeletion = lock.withLock {
        withContext(dispatcher + NonCancellable) {
            val file = Path(directory, "${address.hex}$SEED_SUFFIX")
            try {
                if (!SystemFileSystem.exists(file)) return@withContext KeyDeletion.NotFound
                SystemFileSystem.delete(file)
                KeyDeletion.Deleted
            } catch (e: Exception) {
                report("could not delete the envelope of ${address.hex} (${e::class.simpleName})")
                KeyDeletion.Failed
            }
        }
    }

    private suspend fun import(words: List<String>, longEnough: Boolean, password: ByteArray): KeyImport {
        val provisioned = hardware?.isProvisioned() ?: true
        sweepTemporaries()
        val envelopes = envelopes()
        if (hardwareKeyLost(provisioned, envelopes)) return KeyImport.Unavailable
        val protection = protection()
        val seed = Bip39.seed(words)
        try {
            val account = firstAddressOf(seed)
            val address = account.hex
            // A password is set once a seed is stored, and this must be the same one. Its
            // own envelope is asked first, so an import that is already done stays cheap; and
            // at most MAX_PASSWORD_CHECKS are asked, so a mistyped password costs that many
            // Argon2id runs, not one per account.
            val stored = envelopes.sortedBy { if (it.address == address) 0 else 1 }.take(MAX_PASSWORD_CHECKS)
            var alreadyStored = false
            var verified = false
            // One envelope's GCM tag cannot tell a wrong password from damage to that file, so
            // a refusal does not end the search: another envelope may still open. The extra
            // Argon2id runs happen only when the password is wrong or a file is damaged.
            val refused = mutableListOf<String>()
            for (envelope in stored) {
                when (Envelope.verify(envelope.sealed, password, hardware)) {
                    Envelope.Opened.Verified -> {
                        verified = true
                        alreadyStored = envelope.address == address
                        break
                    }
                    Envelope.Opened.WrongPassword -> refused += envelope.address
                    // Damaged past what canOpen sees: passed over like an unreadable file.
                    else -> report("the envelope of ${envelope.address} cannot be opened")
                }
            }
            // Every envelope is under the one wallet password: none opened and one refused it,
            // so it is wrong. Sealing anyway would put the wallet under two passwords.
            if (!verified && refused.isNotEmpty()) return KeyImport.WrongPassword
            // One opened, so those that refused the same password are damaged.
            refused.forEach { report("the envelope of $it does not open under the wallet password") }
            if (!verified && !longEnough) return KeyImport.PasswordTooShort
            if (!alreadyStored) {
                // Also over a file of this name that cannot be opened: its seed is at hand.
                val sealed = Envelope.seal(seed, address, password, hardware, cost(protection))
                val written = withContext(NonCancellable) {
                    try {
                        EnvelopeCrypto.createPrivateDirectory(directory)
                        val json = JSON.encodeToString(SealedSeed.serializer(), sealed)
                        val file = Path(directory, "$address$SEED_SUFFIX")
                        EnvelopeCrypto.writeDurably(file.toString(), json.encodeToByteArray())
                        true
                    } catch (e: Exception) {
                        report("could not store the envelope (${e::class.simpleName})")
                        false
                    }
                }
                if (!written) return KeyImport.NotSaved
            }
            return KeyImport.Stored(account, protection)
        } finally {
            seed.fill(0)
        }
    }

    /** The lighter cost only where every guess really needs the device's secure hardware. */
    private fun cost(protection: KeyProtection): Envelope.Cost {
        val everyGuessInHardware = protection == KeyProtection.HARDWARE && hardware?.everyGuessNeedsDevice == true
        return if (everyGuessInHardware) HARDWARE_COST else PASSWORD_ONLY_COST
    }

    /**
     * Envelopes sealed with this device's kind of hardware secret exist, but its key does not:
     * lost (an OS upgrade, a cleared key store, a deleted enclave state). The envelopes cannot
     * be opened any more, and a new key must not be made beside them.
     */
    private fun hardwareKeyLost(provisioned: Boolean, envelopes: List<Stored>): Boolean {
        if (provisioned || envelopes.isEmpty()) return false
        report("the hardware key the envelopes were sealed with is gone")
        return true
    }

    private class Stored(val address: String, val sealed: SealedSeed)

    /** The envelopes this device may try to open; every other `*.seed` file is reported and passed over. */
    private fun envelopes(): List<Stored> {
        val dir = Path(directory)
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir)
            .filter { it.name.endsWith(SEED_SUFFIX) }
            .sortedBy { it.name }
            .mapNotNull { file ->
                val address = file.name.removeSuffix(SEED_SUFFIX)
                val sealed = read(file)
                when {
                    sealed == null -> null
                    !Envelope.canOpen(sealed, hardware, address) -> {
                        report("the envelope at $file is not one this device can open")
                        null
                    }
                    else -> Stored(address, sealed)
                }
            }
    }

    private fun read(file: Path): SealedSeed? = try {
        JSON.decodeFromString(SealedSeed.serializer(), SystemFileSystem.source(file).buffered().use { it.readString() })
    } catch (e: Exception) {
        report("the envelope at $file cannot be read (${e::class.simpleName})")
        null
    }

    /**
     * Deletes what a crashed write left behind. Every write here (the envelopes, and the
     * hardware secret's own state during [Envelope.seal] or [Envelope.verify]) happens under
     * this class's lock, which the caller holds.
     */
    private fun sweepTemporaries() {
        val dir = Path(directory)
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).filter { it.name.endsWith(TEMPORARY_SUFFIX) }.forEach { file ->
            try {
                SystemFileSystem.delete(file, mustExist = false)
            } catch (e: Exception) {
                report("could not delete $file (${e::class.simpleName})")
            }
        }
    }

    private companion object {
        val JSON = Json

        /**
         * With a secret in secure hardware every guess needs the device (rule 2), so the KDF is sized for
         * the slowest supported phone: the 64 MiB floor, three passes.
         */
        val HARDWARE_COST = Envelope.Cost(opsLimit = 3, memLimitBytes = 64L * 1024 * 1024)

        /**
         * Without one (or with a key store outside secure hardware, whose key a copy of the
         * device's storage yields), a copied envelope can be attacked offline: four times the
         * memory, four passes.
         */
        val PASSWORD_ONLY_COST = Envelope.Cost(opsLimit = 4, memLimitBytes = 256L * 1024 * 1024)

        const val SEED_SUFFIX = ".seed"

        /** 128 bits: a 12-word mnemonic, as the wallets the user may restore it into make. */
        const val NEW_MNEMONIC_ENTROPY_BYTES = 16

        val PASSWORD_ONLY_KINDS = setOf(KeyProtection.PASSWORD_ONLY, KeyProtection.PASSWORD_ONLY_TPM_NOT_USED)

        /**
         * How many envelopes a password is checked against: the account's own and two more, so
         * up to two damaged but well-formed envelopes do not lock the right password out.
         */
        const val MAX_PASSWORD_CHECKS = 3

        fun report(what: String) = println("Serotine: $what")
    }
}
