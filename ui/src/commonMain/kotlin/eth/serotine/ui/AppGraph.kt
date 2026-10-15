package eth.serotine.ui

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountDerivation
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.ui.home.HomeViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportViewModel

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
    private val accountDerivation: AccountDerivation,
    /** Balances verified by the embedded light client of [network]. */
    private val nativeBalances: NativeBalances,
    /** That light client's own state. */
    private val lightClient: LightClient,
    /** The network the app works on: [Network.SEPOLIA], the only one (CLAUDE.md, *Data sources*). */
    private val network: Network,
) {
    fun homeViewModel(): HomeViewModel =
        HomeViewModel(
            ObserveAccounts(accounts),
            ObserveNativeBalance(nativeBalances),
            ObserveLightClient(lightClient),
            network,
        )

    fun mnemonicImportViewModel(): MnemonicImportViewModel =
        MnemonicImportViewModel(ImportMnemonicAccount(accountDerivation, accounts))
}
