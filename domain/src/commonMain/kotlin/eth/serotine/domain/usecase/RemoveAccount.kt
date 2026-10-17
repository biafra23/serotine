package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Takes an account out of the wallet, and with it the key this device holds for it: the
 * entry first, then the key, so a failure between the two leaves a key without an entry
 * (re-importing the phrase finds it again) and never an entry that claims a key that is
 * gone. One step that a cancelled caller does not cut in half. The recovery phrase is the
 * only way back for a key, which the screen says before it asks for this.
 *
 * It runs under the app's [WalletChanges], so an import cannot change the entry's custody,
 * or store a key for it, between the read below and the removal: the custody read is the one
 * removed, and a watched account's removal leaves the key store alone.
 */
class RemoveAccount(
    private val accounts: AccountRepository,
    private val keys: KeyStore,
    /** The app's one [WalletChanges]: a removal never interleaves with an import. */
    private val changes: WalletChanges,
) {
    suspend operator fun invoke(address: Address): AccountRemoval =
        withContext(NonCancellable) { changes.serialized { remove(address) } }

    private suspend fun remove(address: Address): AccountRemoval {
        val account = when (val list = accounts.observeAccounts().first()) {
            is AccountList.Unreadable -> return AccountRemoval.NotSaved
            is AccountList.Loaded -> list.accounts.firstOrNull { it.address == address }
        } ?: return AccountRemoval.NotFound
        when (accounts.remove(address)) {
            AccountChange.Changed -> Unit
            AccountChange.NotFound -> return AccountRemoval.NotFound
            AccountChange.NotSaved -> return AccountRemoval.NotSaved
        }
        return when (account.custody) {
            AccountCustody.Watched -> AccountRemoval.Removed
            AccountCustody.InKeyStore -> when (keys.delete(address)) {
                KeyDeletion.Deleted, KeyDeletion.NotFound -> AccountRemoval.Removed
                KeyDeletion.Failed -> AccountRemoval.RemovedKeyKept
            }
        }
    }
}

/** The outcome of [RemoveAccount]. */
sealed interface AccountRemoval {
    /** The account is out of the wallet, and no key for it is left on this device. */
    data object Removed : AccountRemoval

    /**
     * The account is out of the list, but its key could not be deleted and is still stored
     * on this device: importing its phrase again brings the account back without a new key.
     */
    data object RemovedKeyKept : AccountRemoval

    /** The account is not in the list; nothing changed. */
    data object NotFound : AccountRemoval

    /** The list could not be saved, or cannot be read; nothing changed. */
    data object NotSaved : AccountRemoval
}
