package eth.serotine.domain.usecase

import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyStore

/**
 * Turns on biometric unlock (docs/wallet-ux.md, Settings → Security; docs/keys-and-signing.md,
 * rule 3): the user's opt-in, given with the wallet password, which opens every stored key's
 * envelope so the biometric path can wrap it. The password path stays as it was.
 */
class EnableBiometricUnlock(private val keys: KeyStore) {
    suspend operator fun invoke(password: WalletPassword): BiometricChange = keys.enableBiometrics(password)
}

/**
 * Turns biometric unlock off: the path's hardware key and its wraps are deleted (rule 4). No
 * password is asked: it takes a way in away, and the password still opens every key.
 */
class DisableBiometricUnlock(private val keys: KeyStore) {
    suspend operator fun invoke(): BiometricChange = keys.disableBiometrics()
}
