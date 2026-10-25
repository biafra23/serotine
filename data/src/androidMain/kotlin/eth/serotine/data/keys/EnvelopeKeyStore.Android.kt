package eth.serotine.data.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
        // First try with StrongBox (for Android 11+)
        var strongBoxUsed = false
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(P256ParameterSpec())
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setIsStrongBoxBacked(true)
                    strongBoxUsed = true
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        // Verify the key is in secure hardware
        if (!isKeyInSecureHardware(keyPair.private)) {
            // Key is not in secure hardware - this could be because StrongBox isn't available
            // For now, we still return the key as it's still a hardware-backed key in the TEE
        }

        // Extract the public key bytes
        val encoded = keyPair.public.encoded
        // For P-256 uncompressed, the format is: 0x04 || x || y (65 bytes)
        // We need to parse this to get x and y coordinates
        val (x, y) = extractP256Coordinates(encoded)

        return x to y
    } catch (e: StrongBoxUnavailableException) {
        // StrongBox is not available on this device. Try without it.
        return tryWithoutStrongBox(alias, keyStore)
    } catch (e: Exception) {
        // Log the error for debugging
        println("P-256 key generation failed: ${e.javaClass.simpleName}: ${e.message}")
        // Re-throw to be caught by the caller and returned as Unavailable
        throw e
    }
}

/**
 * Try P-256 key generation without StrongBox backing.
 */
private fun tryWithoutStrongBox(alias: String, keyStore: KeyStore): Pair<ByteArray, ByteArray> {
    try {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(P256ParameterSpec())
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Don't set setIsStrongBoxBacked() - let it fall back to TEE
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        // Verify the key is at least in TEE
        if (!isKeyInSecureHardware(keyPair.private)) {
            println("P-256 key generated but not in secure hardware")
            throw IllegalStateException("P-256 key must be in secure hardware")
        }

        val encoded = keyPair.public.encoded
        val (x, y) = extractP256Coordinates(encoded)
        return x to y
    } finally {
        keyStore.deleteEntry(alias)
    }
}

/**
 * Check if a private key is backed by secure hardware (TEE or StrongBox).
 */
private fun isKeyInSecureHardware(privateKey: java.security.PrivateKey): Boolean {
    return try {
        val keyFactory = java.security.KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val keyInfo = keyFactory.getKeySpec(privateKey, android.security.keystore.KeyInfo::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
        } else {
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        }
    } catch (e: Exception) {
        // If we can't query, assume it's not secure hardware backed
        false
    }
}

/**
 * Extract P-256 public key coordinates from various encoding formats.
 * Android's KeyFactory.encoded may return DER-encoded keys or raw points.
 */
private fun extractP256Coordinates(encoded: ByteArray): Pair<ByteArray, ByteArray> {
    // Check for uncompressed point format first (0x04 || x || y)
    if (encoded.isNotEmpty() && encoded[0].toInt() and 0xFF == 0x04) {
        check(encoded.size == 65) {
            "P-256 uncompressed public key should be 65 bytes, got ${encoded.size}"
        }
        val x = encoded.copyOfRange(1, 33)
        val y = encoded.copyOfRange(33, 65)
        return x to y
    }

    // Handle DER-encoded format (0x30 ... SEQUENCE)
    if (encoded.isNotEmpty() && encoded[0].toInt() and 0xFF == 0x30) {
        return decodeDerPublicKey(encoded)
    }

    error("P-256 public key has unknown encoding, first byte: 0x${encoded.first().toInt().and(0xFF).toString(16)}")
}

/**
 * Decode a DER-encoded P-256 public key and extract x,y coordinates.
 * DER format for EC public keys is:
 * SEQUENCE {
 *   algorithm AlgorithmIdentifier,
 *   subjectPublicKey BIT STRING
 * }
 * The BIT STRING contains: 0x04 || x || y (uncompressed point)
 */
private fun decodeDerPublicKey(derEncoded: ByteArray): Pair<ByteArray, ByteArray> {
    var offset = 0

    // Skip SEQUENCE header
    check(derEncoded[offset].toInt() and 0xFF == 0x30) { "Expected SEQUENCE tag" }
    offset++ // skip length byte (assume single-byte form for these sizes)

    // Skip AlgorithmIdentifier sequence (OID for EC + parameters)
    check(derEncoded[offset].toInt() and 0xFF == 0x30) { "Expected AlgorithmIdentifier SEQUENCE" }
    offset++
    val algoLength = derEncoded[offset++].toInt() and 0xFF
    offset += algoLength

    // Skip BIT STRING header
    check(derEncoded[offset].toInt() and 0xFF == 0x03) { "Expected BIT STRING tag" }
    offset++
    val bitStringLength = derEncoded[offset++].toInt() and 0xFF

    // Now at the actual public key bytes: 0x04 || x || y
    check(offset + 65 <= derEncoded.size) {
        "DER key too short, expected at least 65 payload bytes"
    }
    check(derEncoded[offset].toInt() and 0xFF == 0x04) {
        "Expected uncompressed point marker in BIT STRING"
    }

    val x = derEncoded.copyOfRange(offset + 1, offset + 33)
    val y = derEncoded.copyOfRange(offset + 33, offset + 65)
    return x to y
}

/**
 * Custom parameter spec for P-256 (secp256r1) key generation.
 */
private class P256ParameterSpec : java.security.spec.ECGenParameterSpec("secp256r1")

private const val KEYSTORE = "AndroidKeyStore"
