package eth.serotine.domain.usecase

import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow

/**
 * The accounts the wallet manages, as a stream the Assets tab renders. One use case per
 * user-visible capability: a ViewModel depends on use cases, never on a repository, so a
 * screen's needs stay readable in its constructor and a port can change without touching
 * the UI.
 */
class ObserveAccounts(private val accounts: AccountRepository) {
    operator fun invoke(): Flow<AccountList> = accounts.observeAccounts()
}
