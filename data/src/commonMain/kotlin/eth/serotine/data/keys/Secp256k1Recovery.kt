package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Signature
import eth.serotine.domain.repository.SignatureRecovery
import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * [SignatureRecovery] with libsecp256k1 (`secp256k1_ecdsa_recover`, native code, as the key
 * adapter signs): the public key a signature and its recovery id name over the digest, as given,
 * and its Ethereum address. Public data only; no key is held. A signature that names no key, or
 * a native library that fails, recovers to nothing.
 */
class Secp256k1Recovery : SignatureRecovery {

    override fun recover(digest: Digest, signature: Signature): Address? = try {
        val compact = (signature.r + signature.s).fromHex()
        // Uncompressed: 0x04 || X || Y.
        val publicKey = Secp256k1.ecdsaRecover(compact, digest.hex.fromHex(), signature.recoveryId)
        if (publicKey.size != UNCOMPRESSED) {
            null
        } else {
            val hash = Keccak256().digest(publicKey.copyOfRange(1, UNCOMPRESSED))
            Address.of("0x" + hash.copyOfRange(ADDRESS_FROM, hash.size).toHex())
        }
    } catch (e: Throwable) {
        // Throwable: libsecp256k1 is native code; a broken binding fails with an Error.
        null
    }

    private companion object {
        const val UNCOMPRESSED = 65
        const val ADDRESS_FROM = 12
    }
}
