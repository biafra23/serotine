package eth.serotine.data.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import kotlin.experimental.and

/**
 * Android implementation of [newHardwareP256Key] for [EnvelopeKeyStore].
 * Generates a P-256 key in the Android Keystore (StrongBox where available) and returns its
 * public key coordinates.
 */
internal actual fun generateP256HardwareKey(): Pair<ByteArray, ByteArray> {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    val alias = "serotine.p256.hardware.${System.currentTimeMillis()}"

    try {
        // Generate a P-256 key pair
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(P256ParameterSpec())
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        // Extract the public key bytes
        val encoded = keyPair.public.encoded
        // For P-256 uncompressed, the format is: 0x04 || x || y (65 bytes)
        // We need to parse this to get x and y coordinates
        val (x, y) = extractP256Coordinates(encoded)

        return x to y
    } finally {
        keyStore.deleteEntry(alias)
    }
}

/**
 * P-256 public key encoding is ANSI X.963 uncompressed format: 0x04 || x || y
 * where x and y are each 32 bytes for P-256.
 */
private fun extractP256Coordinates(encoded: ByteArray): Pair<ByteArray, ByteArray> {
    check(encoded.isNotEmpty() && encoded[0].toInt() and 0xFF == 0x04) {
        "P-256 public key should start with 0x04 (uncompressed), got 0x${encoded[0].toInt().and(0xFF).toString(16)}"
    }
    check(encoded.size == 65) {
        "P-256 uncompressed public key should be 65 bytes, got ${encoded.size}"
    }

    val x = encoded.copyOfRange(1, 33)
    val y = encoded.copyOfRange(33, 65)
    return x to y
}

/**
 * Custom parameter spec for P-256 (secp256r1) key generation.
 */
private class P256ParameterSpec : java.security.spec.ECGenParameterSpec("secp256r1")

private const val KEYSTORE = "AndroidKeyStore"
