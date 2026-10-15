package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.MnemonicProblem
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountDerivation
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.Derivation
import kotlinx.coroutines.flow.first

/**
 * Adds the first account of a BIP-39 mnemonic to the wallet: the "add account from a seed
 * phrase" capability. The account is an [AccountKind.EOA] named "Account N": N is one more
 * than the number of accounts, raised past any such name already taken. One of the two use
 * cases the [Mnemonic] passes through (CLAUDE.md, *Trust*): it hands the words to the key
 * adapter and keeps nothing of them.
 */
class ImportMnemonicAccount(
    private val derivation: AccountDerivation,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(mnemonic: Mnemonic): MnemonicImport =
        when (val derived = derivation.deriveFirstAccount(mnemonic)) {
            is Derivation.Invalid -> MnemonicImport.Invalid(derived.problem)
            Derivation.Unavailable -> MnemonicImport.Unavailable
            is Derivation.Derived -> addOrFind(derived.address)
        }

    /**
     * The entry under [address]: the existing one, or a new one this call adds. Read and add
     * are two steps, so another writer can get in between; then the next round reads again
     * rather than assume what that writer left — an entry, or one already removed again.
     */
    private suspend fun addOrFind(address: Address): MnemonicImport {
        while (true) {
            val current = when (val list = accounts.observeAccounts().first()) {
                is AccountList.Loaded -> list.accounts
                is AccountList.Unreadable -> return MnemonicImport.NotSaved
            }
            current.firstOrNull { it.address == address }?.let { return MnemonicImport.AlreadyAdded(it) }
            val account = Account(address, AccountKind.EOA, nextFreeLabel(current))
            when (accounts.add(account)) {
                AccountAdd.Added -> return MnemonicImport.Added(account)
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

    /** [account] is new and now in the wallet. */
    data class Added(val account: Account) : MnemonicImport

    /** The words' first account is already in the wallet as [account]; nothing changed. */
    data class AlreadyAdded(val account: Account) : MnemonicImport

    /** The words are not a BIP-39 mnemonic, for the reason [problem] gives; nothing changed. */
    data class Invalid(val problem: MnemonicProblem) : MnemonicImport

    /** The key adapter cannot run on this device; nothing changed. */
    data object Unavailable : MnemonicImport

    /** The account was derived, but the account list could not be stored or read; nothing changed. */
    data object NotSaved : MnemonicImport
}
