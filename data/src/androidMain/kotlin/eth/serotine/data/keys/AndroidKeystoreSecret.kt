package eth.serotine.data.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import eth.serotine.domain.repository.KeyProtection
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The envelope's hardware factor on Android (docs/keys-and-signing.md, *The envelope*,
 * `HW_pw`): an HMAC-SHA256 key generated inside the Android Keystore, in StrongBox where the
 * device has one and in the TEE otherwise, with no user-authentication requirement. It
 * never leaves the Keystore: [mix] has the Keystore compute `HMAC(HW_pw, P)`, so every
 * password guess against an envelope needs this device (rule 2).
 *
 * [protection] is what the Keystore says about where the key lives (rule 5): an emulator
 * or a device without secure hardware gets a software key, and the user is told.
 */
class AndroidKeystoreSecret(private val alias: String = "serotine.envelope.password.v1") : HardwareSecret {

    override val tag: String = "android-keystore-hmac-sha256-v1"

    /** The HMAC runs inside the Keystore for every input. */
    override val everyGuessNeedsDevice: Boolean = true

    override val protection: KeyProtection by lazy { protectionOf(key()) }

    override fun mix(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        return mac.doFinal(input)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException is one; a secure element that is present but
            // failing throws a plain ProviderException. Either way the TEE holds the key.
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun protectionOf(key: SecretKey): KeyProtection {
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val inHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
        return if (inHardware) KeyProtection.HARDWARE else KeyProtection.SOFTWARE_KEY_STORE
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}
