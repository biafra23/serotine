package eth.serotine.data.keys

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The biometric path's key on Android (docs/keys-and-signing.md, `HW_bio`, rules 3 to 5): an
 * RSA-2048 key pair in the Android Keystore, in StrongBox where the device has one and in the TEE
 * otherwise, whose private half decrypts only after a per-use strong biometric
 * (`AUTH_BIOMETRIC_STRONG`, a zero timeout; the `-1` duration on API 29), only on an unlocked
 * device, and is invalidated when the enrolled biometrics change. A key the Keystore does not
 * keep in secure hardware is deleted, not used.
 *
 * Wrapping a DEK uses the public half, outside the Keystore, so it needs no biometric: turning
 * the path on, and importing a key while it is on, ask only for the password. Unwrapping goes
 * through [prompter], the host's `BiometricPrompt`, with the decrypting cipher as its
 * `CryptoObject`. RSA-OAEP with SHA-256 and MGF1 with SHA-1, the padding the Keystore decrypts on
 * every supported release.
 */
class AndroidBiometricKey(
    private val context: Context,
    private val prompter: BiometricPrompter,
    private val alias: String = "serotine.envelope.biometric.v1",
) : BiometricKey {

    override val tag: String = "android-keystore-rsa-oaep-v1"

    override fun unavailable(): BiometricUnavailable? {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return BiometricUnavailable.NO_HARDWARE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (strong == BiometricManager.BIOMETRIC_SUCCESS) return null
            // Class 2 only (a camera face unlock, say): never good enough (rule 3), and said so.
            val weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            return when {
                weak == BiometricManager.BIOMETRIC_SUCCESS -> BiometricUnavailable.WEAK_ONLY
                strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricUnavailable.NOT_ENROLLED
                else -> BiometricUnavailable.NO_HARDWARE
            }
        }
        // API 29 knows no classes; a key bound to biometrics opens for a strong one only.
        @Suppress("DEPRECATION")
        return when (manager.canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricUnavailable.NOT_ENROLLED
            else -> BiometricUnavailable.NO_HARDWARE
        }
    }

    override fun exists(): Boolean = keyStore().containsAlias(alias)

    override fun isInvalidated(): Boolean = try {
        // A per-use key initializes without a biometric; an invalidated one refuses at once.
        privateKey()?.let(::decrypting)
        false
    } catch (e: KeyPermanentlyInvalidatedException) {
        true
    } catch (e: UnrecoverableKeyException) {
        // A key the Keystore will not hand out at all, as some devices answer for an invalidated one.
        true
    } catch (e: UserNotAuthenticatedException) {
        // A locked device (setUnlockedDeviceRequired): nothing changed, the key is fine.
        false
    }

    @Synchronized
    override fun create(): Boolean {
        delete()
        try {
            generate(strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException is one; a failing secure element throws a plain one.
            generate(strongBox = false)
        }
        val key = privateKey() ?: return false
        if (!inSecureHardware(key)) {
            delete()
            return false
        }
        return true
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val certificate = keyStore().getCertificate(alias) ?: throw GeneralSecurityException("no biometric key")
        // The public half re-made outside the Keystore: encrypting needs no biometric there.
        val public = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            .generatePublic(X509EncodedKeySpec(certificate.publicKey.encoded))
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, public, OAEP) }.doFinal(dek)
    }

    override suspend fun unwrap(wrapped: ByteArray, reason: String): BiometricUnwrap {
        val cipher = try {
            val key = privateKey() ?: return BiometricUnwrap.Unusable(BiometricUnusable.OFF)
            decrypting(key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
        } catch (e: UnrecoverableKeyException) {
            return BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
        } catch (e: GeneralSecurityException) {
            return BiometricUnwrap.Failed
        }
        return when (val result = prompter.authenticate(cipher, reason)) {
            is PromptResult.Authenticated -> try {
                BiometricUnwrap.Dek(result.cipher.doFinal(wrapped))
            } catch (e: GeneralSecurityException) {
                BiometricUnwrap.Failed
            }
            PromptResult.Cancelled -> BiometricUnwrap.Cancelled
            PromptResult.LockedOut -> BiometricUnwrap.Unusable(BiometricUnusable.LOCKED_OUT)
            PromptResult.NoWindow -> BiometricUnwrap.Unusable(BiometricUnusable.NO_PROMPT)
            PromptResult.Failed -> BiometricUnwrap.Failed
        }
    }

    @Synchronized
    override fun delete() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun decrypting(key: PrivateKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(KEY_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Zero: every use needs its own biometric, never a time window after one.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /** Rule 5: where the Keystore says the key lives. */
    private fun inSecureHardware(key: PrivateKey): Boolean {
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }

    private fun privateKey(): PrivateKey? = keyStore().getKey(alias, null) as? PrivateKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 2048
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
