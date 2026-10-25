package eth.serotine.data.keys

/**
 * Desktop implementation of [newHardwareP256Key] for [EnvelopeKeyStore].
 *
 * - macOS: Uses Security.framework's SecKeyGeneratePair with private key in Secure Enclave
 * - Windows/Linux: Reports Unavailable as there's no standard P-256 hardware API
 */
internal actual fun generateP256HardwareKey(): Pair<ByteArray, ByteArray> {
    // Try macOS implementation first
    if (MacSecurity.onMac) {
        val macSecurity = MacSecurity.instance
            ?: error("Security.framework is not available on this Mac")

        return try {
            macSecurity.generateP256PublicKeyPair()
        } catch (e: Throwable) {
            error("P-256 key generation failed: ${e.message}")
        }
    }

    // Windows and Linux don't have a standard P-256 hardware key API
    error("P-256 hardware key generation is not available on this platform")
}
