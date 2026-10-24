package eth.serotine.data.keys

/**
 * Desktop implementation of [newHardwareP256Key] for [EnvelopeKeyStore].
 *
 * The desktop platforms (macOS, Windows, Linux) don't have a direct P-256 hardware key generation
 * API that matches the iOS Secure Enclave or Android StrongBox pattern. For now, this returns
 * Unavailable - a proper implementation would need to use platform-specific APIs:
 * - macOS: Security framework's SecKeyGeneratePair with private key in Secure Enclave
 * - Windows: CNG with RSA_PSS or ECDSA algorithm and hardware provider
 * - Linux: PKCS#11 with hardware token
 *
 * For the time being, the desktop build will report that P-256 hardware key generation is unavailable.
 */
internal actual fun generateP256HardwareKey(): Pair<ByteArray, ByteArray> {
    error("P-256 hardware key generation is not available on desktop platforms")
}
