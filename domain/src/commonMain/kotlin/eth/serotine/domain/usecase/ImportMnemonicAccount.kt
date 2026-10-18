package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Adds the first account of a BIP-39 mnemonic to the wallet, its seed stored under the
 * wallet password: the "add account from a seed phrase" capability. The key store first,
 * then the account list, so an account never appears whose key was not stored; and the two
 * as one step that a cancelled caller does not cut in half, since a stored key whose
 * account was never added would set the wallet password behind the user's back. The account
 * is an [AccountKind.EOA] held in the key store, named "Account N": N is one more than the number of accounts,
 * raised past any such name already taken. One of the two use cases the [Mnemonic] passes
 * through (CLAUDE.md, *Trust*): it hands the words and the [WalletPassword] to the key store
 * and keeps nothing of either.
 */
class ImportMnemonicAccount(
    private val keys: KeyStore,
    private val accounts: AccountRepository,
    /** The app's one [WalletChanges]: an import never interleaves with a removal. */
    private val changes: WalletChanges,
) {
    suspend operator fun invoke(mnemonic: Mnemonic, password: WalletPassword): MnemonicImport =
        withContext(NonCancellable) { changes.serialized { import(mnemonic, password) } }

    private suspend fun import(mnemonic: Mnemonic, password: WalletPassword): MnemonicImport =
        when (val stored = keys.importMnemonic(mnemonic, password)) {
            is KeyImport.Invalid -> MnemonicImport.Invalid(stored.problem)
            KeyImport.WrongPassword -> MnemonicImport.WrongPassword
            KeyImport.PasswordTooShort -> MnemonicImport.PasswordTooShort
            KeyImport.Unavailable -> MnemonicImport.Unavailable
            KeyImport.NotSaved -> MnemonicImport.KeyNotSaved
            KeyImport.NotEnoughMemory -> MnemonicImport.NotEnoughMemory
            is KeyImport.Stored -> addOrFind(stored.address, stored.protection)
        }

    /**
     * The entry under [address]: the existing one (a watched one now held in the key store),
     * or a new one this call adds. Read and add are two steps, so another writer can get in
     * between; then the next round reads again rather than assume what that writer left — an
     * entry, or one already removed again.
     */
    private suspend fun addOrFind(address: Address, protection: KeyProtection): MnemonicImport {
        while (true) {
            val current = when (val list = accounts.observeAccounts().first()) {
                is AccountList.Loaded -> list.accounts
                is AccountList.Unreadable -> return MnemonicImport.ListNotSaved
            }
            val existing = current.firstOrNull { it.address == address }
            if (existing != null) {
                if (existing.custody == AccountCustody.InKeyStore) return MnemonicImport.AlreadyAdded(existing)
                // A watched address whose key the user has now: it is held, not watched, from here on.
                when (accounts.setCustody(address, AccountCustody.InKeyStore)) {
                    AccountChange.Changed ->
                        return MnemonicImport.KeyAdded(existing.copy(custody = AccountCustody.InKeyStore))
                    AccountChange.NotSaved -> return MnemonicImport.ListNotSaved
                    AccountChange.NotFound -> continue // removed by another writer meanwhile: read again
                }
            }
            val account = Account(address, AccountKind.EOA, nextFreeLabel(current), AccountCustody.InKeyStore)
            when (accounts.add(account)) {
                AccountAdd.Added -> return MnemonicImport.Added(account, protection)
                AccountAdd.NotSaved -> return MnemonicImport.ListNotSaved
                AccountAdd.AlreadyPresent -> Unit // lost a race: read again
            }
        }
    }

}

/** The outcome of [ImportMnemonicAccount]. */
sealed interface MnemonicImport {

    /** [account] is new and in the wallet, its seed stored with [protection]. */
    data class Added(val account: Account, val protection: KeyProtection) : MnemonicImport

    /**
     * The words' first account was in the wallet already, as [account]; its seed is stored
     * now (an account added before keys were kept gets its key this way).
     */
    data class AlreadyAdded(val account: Account) : MnemonicImport

    /**
     * The words' first account was in the wallet as a watched address, [account]; its key is
     * stored now, and the account is held in the key store from here on.
     */
    data class KeyAdded(val account: Account) : MnemonicImport

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives; nothing changed. */
    data class Invalid(val problem: MnemonicProblem) : MnemonicImport

    /** A wallet password is set, and this is not it; nothing changed. */
    data object WrongPassword : MnemonicImport

    /** This import would set the wallet password, and it is too short; nothing changed. */
    data object PasswordTooShort : MnemonicImport

    /** The key store cannot run on this device; nothing changed. */
    data object Unavailable : MnemonicImport

    /** The seed could not be stored; nothing changed. */
    data object KeyNotSaved : MnemonicImport

    /** Not enough free memory to protect the seed under the password; nothing changed. */
    data object NotEnoughMemory : MnemonicImport

    /**
     * The seed is stored (and with it the wallet password, if this import set it), but the
     * account list could not be read or saved, so the account is not in it. Importing the
     * same words again adds it.
     */
    data object ListNotSaved : MnemonicImport
}
