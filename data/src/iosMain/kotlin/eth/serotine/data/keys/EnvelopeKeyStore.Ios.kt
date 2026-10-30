package eth.serotine.data.keys

import eth.serotine.domain.repository.NewHardwareP256Key

/**
 * iOS/macOS implementation of [newHardwareP256Key] for [EnvelopeKeyStore].
 * Generates a P-256 key in the Secure Enclave and returns its public key coordinates.
 */
internal actual fun generateP256HardwareKey(): Pair<ByteArray, ByteArray> {
    val (x, y) = IosSecurity.generateP256PublicKeyPair()
    return x to y
}
