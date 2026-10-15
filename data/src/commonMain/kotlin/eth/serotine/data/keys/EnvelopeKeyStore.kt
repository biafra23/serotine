package eth.serotine.data.keys

import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
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
 * hardware secret, or null on a computer without one: then the envelope stands on the
 * password alone and Argon2id runs harder (rule 9; the owner's ruling for the desktop).
 *
 * The words, the seed, the keys and the password's bytes exist only inside a call, as byte
 * arrays zeroed before it returns. Files are written durably ([EnvelopeCrypto.writeDurably]);
 * the directory is the host's private storage, which is excluded from backups and device
 * transfers (rule 8). Work runs on [dispatcher]; calls are serialized.
 */
class EnvelopeKeyStore(
    private val directory: String,
    private val hardware: HardwareSecret?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KeyStore {

    private val lock = Mutex()

    private val cost: Envelope.Cost
        get() = if (hardware != null) HARDWARE_COST else PASSWORD_ONLY_COST

    override suspend fun protection(): KeyProtection =
        withContext(dispatcher) { hardware?.protection ?: KeyProtection.PASSWORD_ONLY }

    override suspend fun hasPassword(): Boolean = withContext(dispatcher) { envelopes().isNotEmpty() }

    override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
        lock.withLock {
            withContext(dispatcher) {
                if (!EnvelopeCrypto.available()) return@withContext KeyImport.Unavailable
                val words = mnemonic.words
                Bip39.problemWith(words)?.let { return@withContext KeyImport.Invalid(it) }
                val passwordBytes = password.text.encodeToByteArray()
                try {
                    import(words, passwordBytes)
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

    private suspend fun import(words: List<String>, password: ByteArray): KeyImport {
        // A password is set once a seed is stored: this must be the same one.
        envelopes().firstOrNull()?.let { existing ->
            when (val opened = Envelope.open(read(existing) ?: return KeyImport.Unavailable, password, hardware)) {
                is Envelope.Opened.Seed -> opened.bytes.fill(0)
                Envelope.Opened.WrongPassword -> return KeyImport.WrongPassword
                Envelope.Opened.Refused -> return KeyImport.Unavailable
            }
        }
        val seed = Bip39.seed(words)
        try {
            val address = firstAddressOf(seed)
            val file = Path(directory, "${address.hex}.seed")
            if (!SystemFileSystem.exists(file)) {
                val sealed = Envelope.seal(seed, address.hex, password, hardware, cost)
                val written = withContext(NonCancellable) {
                    try {
                        SystemFileSystem.createDirectories(Path(directory))
                        val json = JSON.encodeToString(SealedSeed.serializer(), sealed)
                        EnvelopeCrypto.writeDurably(file.toString(), json.encodeToByteArray())
                        true
                    } catch (e: Exception) {
                        report("could not store the envelope (${e::class.simpleName})")
                        false
                    }
                }
                if (!written) return KeyImport.NotSaved
            }
            return KeyImport.Stored(address, hardware?.protection ?: KeyProtection.PASSWORD_ONLY)
        } finally {
            seed.fill(0)
        }
    }

    private fun envelopes(): List<Path> {
        val dir = Path(directory)
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir).filter { it.name.endsWith(".seed") }.sortedBy { it.name }
    }

    private fun read(file: Path): SealedSeed? = try {
        JSON.decodeFromString(SealedSeed.serializer(), SystemFileSystem.source(file).buffered().use { it.readString() })
    } catch (e: Exception) {
        report("an envelope at $file cannot be read (${e::class.simpleName})")
        null
    }

    private companion object {
        val JSON = Json

        /**
         * With a hardware secret every guess needs the device (rule 2), so the KDF is sized for
         * the slowest supported phone: the 64 MiB floor, three passes.
         */
        val HARDWARE_COST = Envelope.Cost(opsLimit = 3, memLimitBytes = 64L * 1024 * 1024)

        /** Without one, a copied envelope can be attacked offline: four times the memory, four passes. */
        val PASSWORD_ONLY_COST = Envelope.Cost(opsLimit = 4, memLimitBytes = 256L * 1024 * 1024)

        fun report(what: String) = println("Serotine: $what")
    }
}
