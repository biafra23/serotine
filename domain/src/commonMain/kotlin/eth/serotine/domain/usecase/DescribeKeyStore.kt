package eth.serotine.domain.usecase

import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore

/**
 * What the seed screen needs to know before the user types a password: whether one is set
 * already (then it is asked for, otherwise set and repeated), and how this device protects
 * the keys it stores, which the user is told.
 */
class DescribeKeyStore(private val keys: KeyStore) {
    suspend operator fun invoke(): KeyStoreDescription =
        KeyStoreDescription(passwordSet = keys.hasPassword(), protection = keys.protection())
}

/** [passwordSet] once any key is stored; [protection] as [KeyStore.protection] reports it. */
data class KeyStoreDescription(val passwordSet: Boolean, val protection: KeyProtection)
