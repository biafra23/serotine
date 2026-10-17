package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.UserTokens
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.domain.usecase.RemoveAccount
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.accountdetail.AccountDetailViewModel
import eth.serotine.ui.addtoken.AddTokenViewModel
import eth.serotine.ui.assets.AssetsViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportViewModel
import eth.serotine.ui.receive.ReceiveViewModel
import eth.serotine.ui.removeaccount.RemoveAccountViewModel
import eth.serotine.ui.renameaccount.RenameAccountViewModel
import eth.serotine.ui.settings.SettingsViewModel
import eth.serotine.ui.tokens.TokensViewModel
import eth.serotine.ui.watch.WatchViewModel

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
    /** The tokens the wallet knows on [network]. */
    private val tokenCatalog: TokenCatalog,
    /** Token balances verified by that light client. */
    private val tokenBalances: TokenBalances,
    /** What token contracts say about themselves, verified by that light client. */
    private val tokenMetadata: TokenMetadata,
    /** The tokens the user added and hid. */
    private val userTokens: UserTokens,
    /** What is at an address, verified by that light client. */
    private val addressKinds: AddressKinds,
    /** That light client's own state. */
    private val lightClient: LightClient,
    /** The network the app works on: [Network.SEPOLIA], the only one (CLAUDE.md, *Data sources*). */
    private val network: Network,
) {
    /** The one [WalletChanges] of the app: imports and removals never interleave. */
    private val walletChanges = WalletChanges()

    fun assetsViewModel(): AssetsViewModel =
        AssetsViewModel(
            ObserveAccounts(accounts),
            ObserveNativeBalance(nativeBalances),
            ObserveLightClient(lightClient),
            network,
        )

    fun accountDetailViewModel(address: Address): AccountDetailViewModel =
        AccountDetailViewModel(
            address,
            ObserveAccount(accounts),
            ObserveNativeBalance(nativeBalances),
            ObserveTokenHoldings(knownTokens(), tokenBalances),
            SetTokenHidden(userTokens),
            network,
        )

    fun receiveViewModel(address: Address): ReceiveViewModel =
        ReceiveViewModel(address, ObserveAccount(accounts), network)

    fun renameAccountViewModel(address: Address): RenameAccountViewModel =
        RenameAccountViewModel(address, ObserveAccount(accounts), RenameAccount(accounts))

    fun removeAccountViewModel(address: Address): RemoveAccountViewModel =
        RemoveAccountViewModel(address, ObserveAccount(accounts), RemoveAccount(accounts, keys, walletChanges))

    fun watchViewModel(): WatchViewModel =
        WatchViewModel(ObserveAddressKind(addressKinds), ObserveAccount(accounts), WatchAddress(accounts, addressKinds))

    fun settingsViewModel(): SettingsViewModel =
        SettingsViewModel(ObserveLightClient(lightClient), knownTokens(), network)

    fun tokensViewModel(): TokensViewModel =
        TokensViewModel(knownTokens(), SetTokenHidden(userTokens), RemoveToken(userTokens))

    fun addTokenViewModel(): AddTokenViewModel =
        AddTokenViewModel(
            knownTokens(),
            ObserveTokenMetadata(tokenMetadata),
            AddToken(userTokens, tokenMetadata, knownTokens()),
        )

    private fun knownTokens() = ObserveKnownTokens(tokenCatalog, userTokens, tokenMetadata)

    fun mnemonicImportViewModel(): MnemonicImportViewModel =
        MnemonicImportViewModel(ImportMnemonicAccount(keys, accounts, walletChanges), DescribeKeyStore(keys))
}
