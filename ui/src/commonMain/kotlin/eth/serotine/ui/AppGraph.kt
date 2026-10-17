package eth.serotine.ui

import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.ui.home.HomeViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportViewModel
import eth.serotine.ui.update.UpdateRequiredViewModel

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
    /** Where imported seeds are kept: the key adapter's store, behind its port. */
    private val keys: KeyStore,
    /** Balances verified by the embedded light client of [network]. */
    private val nativeBalances: NativeBalances,
    /** That light client's own state. */
    private val lightClient: LightClient,
    /** Network upgrades this version does not support, as peers report them to that light client. */
    private val networkUpgrades: NetworkUpgrades,
    /**
     * The upgrade recorded from earlier reports. The host's own notices write it too, so it is
     * one instance per process, shared with them.
     */
    private val knownUpgrades: KnownUpgradeStore,
    /** The network the app works on: [Network.SEPOLIA], the only one (CLAUDE.md, *Data sources*). */
    private val network: Network,
) {
    private fun observeUpgradeWarning() = ObserveUpgradeWarning(networkUpgrades, knownUpgrades, lightClient)

    fun homeViewModel(): HomeViewModel =
        HomeViewModel(
            ObserveAccounts(accounts),
            ObserveNativeBalance(nativeBalances),
            ObserveLightClient(lightClient),
            observeUpgradeWarning(),
            network,
        )

    fun updateRequiredViewModel(): UpdateRequiredViewModel = UpdateRequiredViewModel(observeUpgradeWarning(), network)

    fun mnemonicImportViewModel(): MnemonicImportViewModel =
        MnemonicImportViewModel(ImportMnemonicAccount(keys, accounts), DescribeKeyStore(keys))
}
