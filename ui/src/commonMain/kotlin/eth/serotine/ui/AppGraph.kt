package eth.serotine.ui

import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.ui.home.HomeViewModel

/**
 * The composition root's contract: everything the shared UI needs, handed in as the
 * domain's PORTS. Each host builds exactly one at start-up with its own adapters
 * (`SerotineApplication` on Android, `main()` on desktop) and passes it to [App]; the UI
 * assembles use cases and ViewModels from it and never constructs an adapter itself.
 *
 * Manual constructor injection, no DI framework: the graph is small enough to read in one
 * place, and a missing dependency is a compile error rather than a start-up crash.
 */
class AppGraph(
    private val accounts: AccountRepository,
) {
    fun homeViewModel(): HomeViewModel = HomeViewModel(ObserveAccounts(accounts))
}
