package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.ReceiptTracker
import eth.serotine.domain.repository.SentTransfers
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UserTokens
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.DisableBiometricUnlock
import eth.serotine.domain.usecase.EnableBiometricUnlock
import eth.serotine.domain.usecase.ImportMnemonicAccount
import eth.serotine.domain.usecase.NewRecoveryPhrase
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressKind
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.QuoteTransfer
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.RemoveAccount
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.RenameAccount
import eth.serotine.domain.usecase.ResolveRecipient
import eth.serotine.domain.usecase.SendTransfer
import eth.serotine.domain.usecase.SetDisplayCurrency
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.accountdetail.AccountDetailViewModel
import eth.serotine.ui.addtoken.AddTokenViewModel
import eth.serotine.ui.assets.AssetsViewModel
import eth.serotine.ui.createaccount.CreateAccountViewModel
import eth.serotine.ui.currency.CurrencyViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportViewModel
import eth.serotine.ui.receive.ReceiveViewModel
import eth.serotine.ui.removeaccount.RemoveAccountViewModel
import eth.serotine.ui.renameaccount.RenameAccountViewModel
import eth.serotine.ui.security.SecurityViewModel
import eth.serotine.ui.send.SendViewModel
import eth.serotine.ui.settings.SettingsViewModel
import eth.serotine.ui.tokens.TokensViewModel
import eth.serotine.ui.transferstatus.TransferStatusViewModel
import eth.serotine.ui.update.UpdateRequiredViewModel
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
    /** Prices from the network's pinned Chainlink feeds, verified by that light client. */
    private val priceFeeds: PriceFeeds,
    /** The user's choice of display currency. */
    private val displayCurrency: DisplayCurrencySetting,
    /** What is at an address, verified by that light client. */
    private val addressKinds: AddressKinds,
    /** That light client's own state. */
    private val lightClient: LightClient,
    /** Network upgrades this version does not support, as peers report them to that light client. */
    private val networkUpgrades: NetworkUpgrades,
    /**
     * The upgrade recorded from earlier reports. The host's own notices write it too, so it is
     * one instance per process, shared with them.
     */
    private val knownUpgrades: KnownUpgradeStore,
    /** What a transfer needs from the chain before it is signed, verified by that light client. */
    private val transactionInputs: TransactionInputs,
    /** Hands a signed transfer to that light client's peers. */
    private val broadcaster: Broadcaster,
    /** A sent transfer's receipt, verified by that light client. */
    private val receipts: ReceiptTracker,
    /** ENS names, resolved by that light client against finalized state. */
    private val names: NameResolver,
    /**
     * Signs with the keys held in [keys]: the key adapter's signer, the same instance as [keys],
     * whose calls take turns (docs/keys-and-signing.md).
     */
    private val signer: Signer,
    /** The transfers this wallet signed, kept across restarts. */
    private val sentTransfers: SentTransfers,
    /** The network the app works on: [Network.SEPOLIA], the only one (CLAUDE.md, *Data sources*). */
    private val network: Network,
) {
    /** The one [WalletChanges] of the app: imports and removals never interleave. */
    private val walletChanges = WalletChanges()

    private fun observeUpgradeWarning() = ObserveUpgradeWarning(networkUpgrades, knownUpgrades, lightClient)

    fun assetsViewModel(): AssetsViewModel =
        AssetsViewModel(
            ObservePortfolio(ObserveAccounts(accounts), accountValue(), displayCurrencies()),
            ObserveSafes(accounts, ObserveSafe(addressKinds, accounts)),
            ObserveLightClient(lightClient),
            observeUpgradeWarning(),
            network,
        )

    fun accountDetailViewModel(address: Address): AccountDetailViewModel =
        AccountDetailViewModel(
            address,
            ObserveAccount(accounts),
            accountValue(),
            ObserveSafe(addressKinds, accounts),
            SetTokenHidden(userTokens),
            network,
        )

    /** Send from the account at [from], with the token at [asset] chosen (null: the network's currency). */
    fun sendViewModel(from: Address, asset: Address?): SendViewModel = SendViewModel(
        from,
        asset,
        ObserveAccounts(accounts),
        accountValue(),
        DescribeKeyStore(keys),
        ResolveRecipient(names),
        QuoteTransfer(transactionInputs, nativeBalances, tokenBalances, sentTransfers, network),
        SendTransfer(signer, broadcaster, sentTransfers),
        RebroadcastTransfer(broadcaster),
        network,
    )

    /** Where the transfer [hash] stands. */
    fun transferStatusViewModel(hash: String): TransferStatusViewModel =
        TransferStatusViewModel(hash, ObserveSentTransfer(sentTransfers, receipts), network)

    fun receiveViewModel(address: Address): ReceiveViewModel =
        ReceiveViewModel(address, ObserveAccount(accounts), network)

    fun renameAccountViewModel(address: Address): RenameAccountViewModel =
        RenameAccountViewModel(address, ObserveAccount(accounts), RenameAccount(accounts))

    fun removeAccountViewModel(address: Address): RemoveAccountViewModel =
        RemoveAccountViewModel(address, ObserveAccount(accounts), RemoveAccount(accounts, keys, walletChanges))

    fun watchViewModel(): WatchViewModel = WatchViewModel(
        ObserveAddressKind(addressKinds),
        ObserveAccounts(accounts),
        WatchAddress(accounts, addressKinds),
    )

    fun settingsViewModel(): SettingsViewModel =
        SettingsViewModel(ObserveLightClient(lightClient), knownTokens(), displayCurrencies(), network)

    fun currencyViewModel(): CurrencyViewModel =
        CurrencyViewModel(displayCurrencies(), SetDisplayCurrency(priceFeeds, displayCurrency))

    fun tokensViewModel(): TokensViewModel =
        TokensViewModel(knownTokens(), SetTokenHidden(userTokens), RemoveToken(userTokens))

    fun addTokenViewModel(): AddTokenViewModel =
        AddTokenViewModel(
            knownTokens(),
            ObserveTokenMetadata(tokenMetadata),
            AddToken(userTokens, tokenMetadata, knownTokens()),
        )

    private fun knownTokens() = ObserveKnownTokens(tokenCatalog, userTokens, tokenMetadata)

    private fun displayCurrencies() = ObserveDisplayCurrencies(priceFeeds, displayCurrency)

    private fun accountValue() = ObserveAccountValue(
        ObserveNativeBalance(nativeBalances),
        ObserveTokenHoldings(knownTokens(), tokenBalances),
        ObserveUsdPrice(priceFeeds),
        displayCurrencies(),
        network,
    )

    fun createAccountViewModel(): CreateAccountViewModel = CreateAccountViewModel(
        NewRecoveryPhrase(keys),
        ImportMnemonicAccount(keys, accounts, walletChanges),
        DescribeKeyStore(keys),
    )

    fun updateRequiredViewModel(): UpdateRequiredViewModel = UpdateRequiredViewModel(observeUpgradeWarning(), network)

    fun securityViewModel(): SecurityViewModel =
        SecurityViewModel(DescribeKeyStore(keys), EnableBiometricUnlock(keys), DisableBiometricUnlock(keys))

    fun mnemonicImportViewModel(): MnemonicImportViewModel =
        MnemonicImportViewModel(ImportMnemonicAccount(keys, accounts, walletChanges), DescribeKeyStore(keys))
}
