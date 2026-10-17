package eth.serotine.android.keys

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import eth.serotine.android.R
import eth.serotine.data.keys.BiometricPrompter
import eth.serotine.data.keys.PromptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * The key adapter's biometric prompt on Android (docs/keys-and-signing.md, rule 3): the
 * platform's `BiometricPrompt`, shown on the activity in front ([foreground]), with the
 * biometric key's cipher as its `CryptoObject`, so only a match the hardware accepts unlocks
 * it. Strong biometrics only (`BIOMETRIC_STRONG` from API 30; a crypto-bound prompt on API 29
 * takes no weaker one), with an explicit confirmation, and "Use the password" as the way out.
 */
class PlatformBiometricPrompter(private val foreground: () -> Activity?) : BiometricPrompter {

    override suspend fun authenticate(cipher: Cipher, reason: String): PromptResult = withContext(Dispatchers.Main) {
        val activity = foreground() ?: return@withContext PromptResult.NoWindow
        suspendCancellableCoroutine<PromptResult> { continuation ->
            fun answer(result: PromptResult) {
                if (continuation.isActive) continuation.resume(result)
            }
            val executor = activity.mainExecutor
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.biometric_prompt_title))
                .setSubtitle(reason)
                .setConfirmationRequired(true)
                .setNegativeButton(activity.getString(R.string.biometric_prompt_use_password), executor) { _, _ ->
                    answer(PromptResult.Cancelled)
                }
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    }
                }
                .build()
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlocked = result.cryptoObject?.cipher
                        answer(if (unlocked != null) PromptResult.Authenticated(unlocked) else PromptResult.Failed)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        answer(
                            when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                -> PromptResult.Cancelled
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
                                -> PromptResult.LockedOut
                                else -> PromptResult.Failed
                            },
                        )
                    }

                    // A finger or face that did not match: the prompt stays up for another try.
                    override fun onAuthenticationFailed() = Unit
                },
            )
        }
    }
}
