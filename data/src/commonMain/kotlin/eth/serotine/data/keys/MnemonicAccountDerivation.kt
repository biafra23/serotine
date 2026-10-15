package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.repository.AccountDerivation
import eth.serotine.domain.repository.Derivation
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * [AccountDerivation] in the key adapter: BIP-39 words in, the address of their first
 * account out (`m/44'/60'/0'/0/0`, the empty passphrase, the English wordlist). The seed
 * and the private key exist only inside one call, as byte arrays zeroed before it returns;
 * nothing is kept, so the account cannot sign until the key store holds its key
 * (docs/keys-and-signing.md, *The envelope*).
 *
 * The 2048 PBKDF2 rounds and the curve work run on [dispatcher], never the caller's thread.
 */
class MnemonicAccountDerivation(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AccountDerivation {

    override suspend fun deriveFirstAccount(mnemonic: Mnemonic): Derivation = withContext(dispatcher) {
        val words = mnemonic.words
        val problem = Bip39.problemWith(words)
        if (problem != null) return@withContext Derivation.Invalid(problem)
        val seed = Bip39.seed(words)
        val key = try {
            Bip32.derive(seed, FIRST_ETHEREUM_ACCOUNT)
        } finally {
            seed.fill(0)
        }
        try {
            Derivation.Derived(addressOf(key))
        } finally {
            key.fill(0)
        }
    }

    private companion object {
        /** BIP-44 `m/44'/60'/0'/0/0`: purpose 44, coin type 60 (Ether), account 0, external chain, index 0. */
        val FIRST_ETHEREUM_ACCOUNT = intArrayOf(
            44 or Bip32.HARDENED,
            60 or Bip32.HARDENED,
            0 or Bip32.HARDENED,
            0,
            0,
        )
    }
}

/** The Ethereum address of [privateKey]: the last 20 bytes of Keccak-256 over the public key's X || Y. */
internal fun addressOf(privateKey: ByteArray): Address {
    val publicKey = Secp256k1.pubkeyCreate(privateKey) // 0x04 || X || Y
    val hash = Keccak256().digest(publicKey.copyOfRange(1, 65))
    return Address.of("0x" + hash.copyOfRange(12, 32).toHex())
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}
