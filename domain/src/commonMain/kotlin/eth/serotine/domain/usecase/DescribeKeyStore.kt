package eth.serotine.domain.usecase

import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription

/**
 * What the seed screen needs to know before the user types a password: whether one is set
 * already (then it is asked for, otherwise set and repeated), and how this device protects
 * the keys it stores, which the user is told — or that the key store does not work here.
 */
class DescribeKeyStore(private val keys: KeyStore) {
    suspend operator fun invoke(): KeyStoreDescription = keys.describe()
}
