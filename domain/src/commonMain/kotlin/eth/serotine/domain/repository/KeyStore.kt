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

    /**
     * What the seed screen needs before a password is typed: whether a wallet password is
     * set and how this device protects the keys it stores (rules 5 and 9), or that the key
     * store does not work here. Never throws.
     */
    suspend fun describe(): KeyStoreDescription

    /**
     * Imports [mnemonic]: checks its words against the BIP-39 English wordlist and their
     * checksum, derives its first account (`m/44'/60'/0'/0/0`, the empty passphrase) and
     * stores its BIP-39 seed in the envelope under [password] and this device's hardware
     * secret where there is one. The first import sets the wallet password, and refuses one
     * shorter than [WalletPassword.MIN_LENGTH]; every later one must give the same password,
     * which is checked by opening a stored envelope first. The seed and the keys exist only
     * inside this call. Never throws.
     */
    suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport

    /**
     * A new BIP-39 mnemonic of 12 words, from 128 bits of the platform's secure randomness,
     * for Create account (docs/wallet-ux.md, screen 12): the backup words shown at creation, the
     * mnemonic exception's other end (CLAUDE.md, *Trust*). Nothing is stored: the words are
     * kept by [importMnemonic] once the user has written them down and checked them. Never
     * throws.
     */
    suspend fun newMnemonic(): NewMnemonic

    /**
     * Deletes the key of [address], the envelope its seed is sealed in, so that nothing on
     * this device can sign for it any more ([KeyDeletion.Deleted]); [KeyDeletion.NotFound]
     * when there is none. Once the last one is gone no wallet password is set: the next
     * import sets one. No password is asked: deleting takes nothing from someone who could
     * clear the app's data anyway, and the recovery phrase restores the key. Never throws.
     */
    suspend fun delete(address: Address): KeyDeletion

    /**
     * Turns on the envelope's biometric path (docs/keys-and-signing.md, rules 3 and 4): a second
     * hardware key that only a per-use strong biometric unlocks, made anew, and each stored key's
     * DEK wrapped by it, so that a signature can be authorized by a fingerprint or a face instead
     * of the password ([Unlock.WithBiometrics]). [password] must be the wallet password: it opens
     * every envelope to reach its DEK, and nothing is turned on when it does not. The new path is
     * then proven once, the way signing uses it: one wrap opened through the system's prompt,
     * which says [confirmation] (display-ready); it is on only when that worked. The password path
     * is untouched, and keys stored while the path is on are wrapped too. Never throws.
     */
    suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange

    /**
     * Turns the biometric path off: its hardware key and every wrap made with it are deleted,
     * a deletion and not a flag (rule 4); the password path is untouched. Never throws.
     */
    suspend fun disableBiometrics(): BiometricChange
}

/** The outcome of [KeyStore.describe]. */
sealed interface KeyStoreDescription {

    /**
     * The key store works: [passwordSet] once a key is stored, [protection] as this device has
     * it, and whether a biometric can authorize a signature here ([biometrics]).
     */
    data class Ready(
        val passwordSet: Boolean,
        val protection: KeyProtection,
        val biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice,
    ) : KeyStoreDescription

    /** The key store cannot run on this device (its crypto library or key store fails). */
    data object Unavailable : KeyStoreDescription
}

/** How a device protects stored keys. */
enum class KeyProtection {
    /** The password and a secret only this device's secure hardware yields: both needed. */
    HARDWARE,

    /** The password and a key-store secret that is not in secure hardware (an emulator, some devices). */
    SOFTWARE_KEY_STORE,

    /** The password alone, stretched harder: Serotine uses no hardware key store on this computer. */
    PASSWORD_ONLY,

    /**
     * The password alone, stretched harder, on a computer whose TPM Serotine does not use yet:
     * Windows and Linux, until their TPM path is built (the owner's ruling of 2026-10-16). The
     * user is told so, a TPM present or not.
     */
    PASSWORD_ONLY_TPM_NOT_USED,
}

/** The envelope's biometric path on this device, as [KeyStore.describe] reports it. */
sealed interface BiometricUnlock {

    /** Serotine has no biometric path on this platform yet (the desktop, iOS until its app). */
    data object NotOnThisDevice : BiometricUnlock

    /** This device cannot offer one now, for [reason]. */
    data class Unavailable(val reason: BiometricUnavailable) : BiometricUnlock

    /** It can be turned on, and is off. */
    data object Off : BiometricUnlock

    /**
     * It is on: its key exists, and a strong biometric can authorize a signature instead of the
     * password whenever the device's sensor can be used.
     */
    data object On : BiometricUnlock

    /**
     * It was on, and a change of the enrolled biometrics invalidated its key (rule 3): it no
     * longer opens anything, and turning it off (which deletes the key) and on again makes a new
     * one. The password still works.
     */
    data object Invalidated : BiometricUnlock
}

/** Why a device cannot offer the biometric path. */
enum class BiometricUnavailable {
    /** No biometric sensor, or none the system can use now. */
    NO_HARDWARE,

    /** A sensor, but no fingerprint or face enrolled. */
    NOT_ENROLLED,

    /** Only weak biometrics (Class 2): they never qualify (rule 3). */
    WEAK_ONLY,

    /** The key the path needs would not be in secure hardware here (rule 5), so it is not made. */
    NOT_IN_SECURE_HARDWARE,
}

/** The outcome of [KeyStore.enableBiometrics] and [KeyStore.disableBiometrics]. */
sealed interface BiometricChange {

    /** The biometric path is on, for every stored key. */
    data object Enabled : BiometricChange

    /** The biometric path is off, its key deleted. */
    data object Disabled : BiometricChange

    /** The password is not the wallet's. Nothing changed. */
    data object WrongPassword : BiometricChange

    /** No key is stored yet, so there is nothing to unlock and no wallet password to check. */
    data object NoKeys : BiometricChange

    /** Serotine has no biometric path on this platform yet. Nothing changed. */
    data object NotOnThisDevice : BiometricChange

    /**
     * This device cannot offer it, for [reason]. Nothing changed when that was known up front;
     * when the platform refused the new key ([BiometricUnavailable.NOT_IN_SECURE_HARDWARE] found
     * only on making it), the biometric path is off.
     */
    data class Unavailable(val reason: BiometricUnavailable) : BiometricChange

    /** The prompt that proves the new path was dismissed. The biometric path is off. */
    data object Cancelled : BiometricChange

    /**
     * The prompt that proves the new path could not confirm it (the sensor busy or locked out, no
     * screen in front, the key refusing on this device). The biometric path is off.
     */
    data object NotConfirmed : BiometricChange

    /**
     * The key store failed. The password path is untouched; the biometric path is as
     * [KeyStore.describe] now reports it.
     */
    data object Failed : BiometricChange
}

/** The outcome of [KeyStore.importMnemonic]. */
sealed interface KeyImport {

    /** The seed is stored; [address] is its first account. */
    data class Stored(val address: Address, val protection: KeyProtection) : KeyImport

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives. */
    data class Invalid(val problem: MnemonicProblem) : KeyImport

    /** A wallet password is set, and this is not it. Nothing was stored. */
    data object WrongPassword : KeyImport

    /** This import would set the wallet password, and it is shorter than [WalletPassword.MIN_LENGTH]. */
    data object PasswordTooShort : KeyImport

    /** The key store cannot run on this device (its crypto library or key store is missing). */
    data object Unavailable : KeyImport

    /** The envelope could not be written. Nothing was stored. */
    data object NotSaved : KeyImport
}

/** The outcome of [KeyStore.newMnemonic]. */
sealed interface NewMnemonic {
    /** [mnemonic] was made; nothing is stored yet. */
    data class Created(val mnemonic: Mnemonic) : NewMnemonic

    /** The key store cannot run on this device. */
    data object Unavailable : NewMnemonic
}

/** The outcome of [KeyStore.delete]. */
sealed interface KeyDeletion {
    /** The key is gone from this device. */
    data object Deleted : KeyDeletion

    /** This device holds no key for the address. */
    data object NotFound : KeyDeletion

    /** The key could not be deleted (the file system refused); it is still stored. */
    data object Failed : KeyDeletion
}
