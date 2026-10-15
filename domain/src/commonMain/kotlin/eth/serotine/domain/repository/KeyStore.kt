package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword

/**
 * The key-store port (docs/keys-and-signing.md, *Where it lives in the architecture*): the
 * lifecycle of the wallet's keys. :data implements it in the key adapter
 * (`eth.serotine.data.keys`), the one place plaintext key material may exist; what comes
 * back up is an address and a verdict, never a key.
 */
interface KeyStore {

    /** How this device protects the keys it stores; the user is told (rules 5 and 9). */
    suspend fun protection(): KeyProtection

    /** Whether a wallet password has been set: true once any key is stored. */
    suspend fun hasPassword(): Boolean

    /**
     * Imports [mnemonic]: checks its words against the BIP-39 English wordlist and their
     * checksum, derives its first account (`m/44'/60'/0'/0/0`, the empty passphrase) and
     * stores its BIP-39 seed in the envelope under [password] and this device's hardware
     * secret where there is one. The first import sets the wallet password; every later one
     * must give the same password, which is checked by opening a stored envelope first. The
     * seed and the keys exist only inside this call. Never throws.
     */
    suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport
}

/** How a device protects stored keys. */
enum class KeyProtection {
    /** The password and a secret only this device's secure hardware yields: both needed. */
    HARDWARE,

    /** The password and a key-store secret that is not in secure hardware (an emulator, some devices). */
    SOFTWARE_KEY_STORE,

    /** The password alone, stretched harder: no hardware key store on this computer. */
    PASSWORD_ONLY,
}

/** The outcome of [KeyStore.importMnemonic]. */
sealed interface KeyImport {

    /** The seed is stored; [address] is its first account. */
    data class Stored(val address: Address, val protection: KeyProtection) : KeyImport

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives. */
    data class Invalid(val problem: MnemonicProblem) : KeyImport

    /** A wallet password is set, and this is not it. Nothing was stored. */
    data object WrongPassword : KeyImport

    /** The key store cannot run on this device (its crypto library or key store is missing). */
    data object Unavailable : KeyImport

    /** The envelope could not be written. Nothing was stored. */
    data object NotSaved : KeyImport
}
