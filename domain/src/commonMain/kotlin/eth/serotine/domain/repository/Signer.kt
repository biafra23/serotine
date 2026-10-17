package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.WalletPassword

/**
 * The signer port (docs/keys-and-signing.md, *Where it lives in the architecture*): a signing
 * request in, a signature out. :data implements it in the key adapter, the one place a key
 * exists, and only inside one call; nothing that comes back up is key material.
 */
interface Signer {
    /**
     * Signs [digest] with the key of [account], held in this device's key store: its envelope
     * is opened with [password] and, where there is one, this device's hardware secret, the key
     * derived from the seed inside it, and the seed, the key and the password's bytes zeroed
     * before this returns. [digest] is signed as given, never hashed again: what it is the hash
     * of is the caller's to know and to have shown the user. Refused when this device holds no
     * key for [account] ([Signing.NoKey]), or the key it holds does not derive [account]. Never
     * throws.
     */
    suspend fun sign(account: Address, digest: Digest, password: WalletPassword): Signing
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
}
