package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import kotlinx.coroutines.flow.first

/**
 * Adds the first account of a BIP-39 mnemonic to the wallet, its seed stored under the
 * wallet password: the "add account from a seed phrase" capability. The key store first,
 * then the account list, so an account never appears whose key was not stored. The account
 * is an [AccountKind.EOA] named "Account N": N is one more than the number of accounts,
 * raised past any such name already taken. One of the two use cases the [Mnemonic] passes
 * through (CLAUDE.md, *Trust*): it hands the words and the [WalletPassword] to the key store
 * and keeps nothing of either.
 */
class ImportMnemonicAccount(
    private val keys: KeyStore,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(mnemonic: Mnemonic, password: WalletPassword): MnemonicImport =
        when (val stored = keys.importMnemonic(mnemonic, password)) {
            is KeyImport.Invalid -> MnemonicImport.Invalid(stored.problem)
            KeyImport.WrongPassword -> MnemonicImport.WrongPassword
            KeyImport.Unavailable -> MnemonicImport.Unavailable
            KeyImport.NotSaved -> MnemonicImport.NotSaved
            is KeyImport.Stored -> addOrFind(stored.address, stored.protection)
        }

    /**
     * The entry under [address]: the existing one, or a new one this call adds. Read and add
     * are two steps, so another writer can get in between; then the next round reads again
     * rather than assume what that writer left — an entry, or one already removed again.
     */
    private suspend fun addOrFind(address: Address, protection: KeyProtection): MnemonicImport {
        while (true) {
            val current = when (val list = accounts.observeAccounts().first()) {
                is AccountList.Loaded -> list.accounts
                is AccountList.Unreadable -> return MnemonicImport.NotSaved
            }
            current.firstOrNull { it.address == address }?.let { return MnemonicImport.AlreadyAdded(it) }
            val account = Account(address, AccountKind.EOA, nextFreeLabel(current))
            when (accounts.add(account)) {
                AccountAdd.Added -> return MnemonicImport.Added(account, protection)
                AccountAdd.NotSaved -> return MnemonicImport.NotSaved
                AccountAdd.AlreadyPresent -> Unit // lost a race: read again
            }
        }
    }

    private fun nextFreeLabel(current: List<Account>): String {
        val taken = current.mapTo(HashSet()) { it.label }
        return generateSequence(current.size + 1) { it + 1 }.map { "Account $it" }.first { it !in taken }
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

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives; nothing changed. */
    data class Invalid(val problem: MnemonicProblem) : MnemonicImport

    /** A wallet password is set, and this is not it; nothing changed. */
    data object WrongPassword : MnemonicImport

    /** The key store cannot run on this device; nothing changed. */
    data object Unavailable : MnemonicImport

    /** The seed or the account list could not be stored; nothing was added. */
    data object NotSaved : MnemonicImport
}
