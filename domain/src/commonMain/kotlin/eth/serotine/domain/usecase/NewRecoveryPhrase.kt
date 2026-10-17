package eth.serotine.domain.usecase

import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.NewMnemonic

/**
 * A new recovery phrase for Create account (docs/wallet-ux.md, screen 12): made by the key
 * adapter, shown on that screen alone, and stored only through [ImportMnemonicAccount] once the user has
 * written it down and checked three of its words.
 */
class NewRecoveryPhrase(private val keys: KeyStore) {
    suspend operator fun invoke(): NewMnemonic = keys.newMnemonic()
}
