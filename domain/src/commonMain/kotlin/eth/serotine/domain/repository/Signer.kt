package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.WalletPassword
import kotlin.time.Duration

/**
 * The signer port (docs/keys-and-signing.md, *Where it lives in the architecture*): a signing
 * request in, a signature out. :data implements it in the key adapter, the one place a key
 * exists, and only inside one call; nothing that comes back up is key material.
 */
interface Signer {
    /**
     * Signs [digest] with the key of [account], held in this device's key store: its envelope
     * is opened as [unlock] says — with the wallet password and, where there is one, this
     * device's hardware secret, or with the DEK a strong biometric unwraps — the key derived from
     * the seed inside it, and the seed, the key and the password's bytes zeroed before this
     * returns. [digest] is signed as given, never hashed again: what it is the hash of is the
     * caller's to know and to have shown the user. Refused when this device holds no key for
     * [account] ([Signing.NoKey]), or the key it holds does not derive [account]. A biometric
     * unlock's deadline is applied, never ignored ([Signing.TimedOut]). Never throws.
     */
    suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing
}

/** How [Signer.sign] opens the account's envelope. */
sealed interface Unlock {

    /** With [password], the wallet password: the path every envelope has. */
    class WithPassword(val password: WalletPassword) : Unlock {
        override fun toString(): String = "WithPassword(redacted)"
    }

    /**
     * With a strong biometric, through the system's prompt, which says [reason] (display-ready:
     * what is being signed). Only where the biometric path is on ([KeyStore.enableBiometrics]).
     * [within], when set, is the deadline the signature must be made in, the prompt's answer
     * included: past it the prompt is dismissed and nothing is signed ([Signing.TimedOut]). A
     * quote ages while the prompt is up, and one past its age is never signed.
     */
    data class WithBiometrics(val reason: String, val within: Duration? = null) : Unlock
}

/** The outcome of [Signer.sign]. */
sealed interface Signing {

    /** [signature] is [Signer.sign]'s digest signed by the account's key. */
    data class Signed(val signature: Signature) : Signing

    /** The password does not open the account's envelope. Nothing was signed. */
    data object WrongPassword : Signing

    /** This device holds no key for the account. Nothing was signed. */
    data object NoKey : Signing

    /** The key store cannot run on this device, or its envelope cannot be opened here. Nothing was signed. */
    data object Unavailable : Signing

    /** The biometric prompt was dismissed. Nothing was signed; the password still works. */
    data object Cancelled : Signing

    /**
     * Not signed within the unlock's deadline ([Unlock.WithBiometrics.within]): the prompt was
     * dismissed, and nothing was signed. What it was to sign is too old by now, and is made again.
     */
    data object TimedOut : Signing

    /**
     * A biometric cannot open this account's envelope now, for [reason]. Nothing was signed;
     * the password still works.
     */
    data class BiometricsUnusable(val reason: BiometricUnusable) : Signing
}

/** Why [Unlock.WithBiometrics] cannot open an envelope. */
enum class BiometricUnusable {
    /**
     * The biometric path is off, or not on this platform at all (the desktop, iOS until its app),
     * or this account's key was stored without it.
     */
    OFF,

    /** A change of the enrolled biometrics invalidated the path's key (rule 3). */
    INVALIDATED,

    /** Too many failed attempts: the system locked biometrics for now. */
    LOCKED_OUT,

    /** The sensor could not be used just now (busy, unable to process, a vendor error). */
    SENSOR_UNAVAILABLE,

    /** No screen of the app is in front to show the prompt on. */
    NO_PROMPT,
}
