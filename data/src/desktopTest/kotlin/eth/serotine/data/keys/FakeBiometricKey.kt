package eth.serotine.data.keys

import eth.serotine.domain.repository.BiometricUnavailable
import eth.serotine.domain.repository.BiometricUnusable
import kotlinx.coroutines.CompletableDeferred
import kotlin.random.Random

/**
 * A [BiometricKey] for tests: a random secret per key made, a wrap that is the DEK masked with
 * it, and a prompt that answers [prompt] (a biometric that matches, unless a test says
 * otherwise). [reasons] records what each prompt said.
 */
internal class FakeBiometricKey(
    var unavailable: BiometricUnavailable? = null,
    var inSecureHardware: Boolean = true,
) : BiometricKey {
    override val tag: String = "fake-biometric-v1"

    var made = 0
    var invalidated = false
    var prompt: BiometricUnwrap? = null
    var failWrap = false
    var failDescribe = false
    var failQueries = false
    var gate: CompletableDeferred<Unit>? = null
    val reasons = mutableListOf<String>()
    private var secret: ByteArray? = null

    override fun unavailable(): BiometricUnavailable? {
        if (failDescribe) throw IllegalStateException("the key store is gone")
        return unavailable
    }

    override fun exists(): Boolean {
        if (failQueries) throw IllegalStateException("the key store is gone")
        return secret != null
    }

    override fun isInvalidated(): Boolean = exists() && invalidated

    override fun create(): Boolean {
        delete()
        made++
        if (!inSecureHardware) return false
        secret = Random(made).nextBytes(32)
        return true
    }

    override fun wrap(dek: ByteArray): ByteArray {
        if (failWrap) throw IllegalStateException("the key store refused")
        return mask(dek, checkNotNull(secret) { "no biometric key" })
    }

    override suspend fun unwrap(wrapped: ByteArray, reason: String): BiometricUnwrap {
        reasons += reason
        gate?.await()
        val secret = secret ?: return BiometricUnwrap.Unusable(BiometricUnusable.OFF)
        if (invalidated) return BiometricUnwrap.Unusable(BiometricUnusable.INVALIDATED)
        return prompt ?: BiometricUnwrap.Dek(mask(wrapped, secret))
    }

    override fun delete() {
        secret = null
        invalidated = false
    }

    private fun mask(bytes: ByteArray, with: ByteArray) =
        ByteArray(bytes.size) { (bytes[it].toInt() xor with[it % with.size].toInt()).toByte() }
}
