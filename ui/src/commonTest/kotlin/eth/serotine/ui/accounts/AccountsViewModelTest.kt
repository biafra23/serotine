package eth.serotine.ui.accounts

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountChange
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.StaleAnchorConsent
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUpgradeWarnings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.RefreshNetworkData
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.SyncPastStaleAnchor
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeFreshness
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientHealth
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.update.UpgradeBannerItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

    private val repository = FakeAccountRepository()
    private val balances = FakeBalances()
    private val lightClient = FakeLightClient()

    /** The networks the user let catch up from a stale checkpoint. */
    private val consented = mutableListOf<Network>()
    private val consent = object : StaleAnchorConsent {
        override fun syncPastStaleAnchor(network: Network) {
            consented += network
        }
    }
    private val upgrades = FakeNetworkUpgrades()
    private val starting = LightClientItem.from(LightClientStatus.Starting, 0)
    private var now = 1_792_100_000L
    private val feeds = FakeFeeds()
    private val kind = MutableStateFlow<KindReading>(KindReading.Syncing)
    private val kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> = kind
    }
    private val currency = MemoryCurrencySetting()
    private val names = FakeNames()
    private val networks = MemoryNetworkSettings()

    /** Sepolia's section with [accounts] under [status]. */
    private fun sepolia(
        status: LightClientItem,
        accounts: List<AccountItem>,
        total: String? = "Checking…",
        totalNote: String? = "Testnet prices · still checking",
    ) = NetworkSection(Network.SEPOLIA.chainId, "Sepolia", testnet = true, on = true, status, accounts, total, totalNote)

    private fun viewModel(): AccountsViewModel {
        val currencies = ObserveDisplayCurrencies(feeds, currency, repository)
        val value = ObserveAccountValue(
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(
                ObserveKnownTokens(FakeTokenCatalog(), MemoryUserTokens(), FakeMetadata()),
                FakeTokenBalances(),
            ),
            ObserveUsdPrice(feeds),
            currencies,
        )
        return AccountsViewModel(
            ObservePortfolio(ObserveAccounts(repository), value, currencies),
            ObserveSafes(repository, ObserveSafe(kinds, repository)),
            ObserveEnsNames(repository, names),
            ObserveLightClient(lightClient),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            ObserveUpgradeWarnings(upgrades, NoRecord, lightClient, networks, repository) { now },
            SyncPastStaleAnchor(lightClient, consent),
            RefreshNetworkData(freshness),
            clock = { now },
            formatTime = { "<$it>" },
        )
    }

    private val freshness = FakeFreshness()

    private val treasury =
        Account(
            Network.SEPOLIA,
            Address.of("0x" + "ab".repeat(20)),
            AccountKind.SAFE,
            "Treasury",
            AccountCustody.InKeyStore,
        )
    private val daily = Account(
        Network.SEPOLIA,
        Address.of("0x" + "cd".repeat(20)),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )

    private fun treasuryItem(balance: String, verified: Boolean) =
        AccountItem(
            "Treasury",
            Eip55.format(treasury.address),
            Network.SEPOLIA.chainId,
            AccountKind.SAFE,
            Control.WATCH_ONLY,
            balance,
            verified,
            kindNote = "Safe",
        )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun loadsUntilTheFirstEmissionThenMirrorsTheAccountsWithTheirBalances() = runTest {
        // viewModelScope runs on Dispatchers.Main; route it through this test's scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        // Nothing collects yet: WhileSubscribed has not started the upstream.
        assertEquals(AccountsUiState.Loading(), viewModel.uiState.value)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(AccountsUiState.Ready(emptyList()), viewModel.uiState.value)

        repository.accounts = listOf(treasury)
        runCurrent()
        assertEquals(
            AccountsUiState.Ready(
                listOf(sepolia(starting, listOf(treasuryItem("Syncing…", verified = false)))),
                total = "Checking…",
                totalLabel = "Total · USD · Sepolia",
                totalNote = "Testnet prices · still checking",
            ),
            viewModel.uiState.value,
        )

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        runCurrent()
        assertEquals(
            treasuryItem("2.5 ETH", verified = true).copy(value = "Checking price…"),
            (viewModel.uiState.value as AccountsUiState.Ready).accounts.single(),
        )
    }

    @Test
    fun aRowIsAlwaysShownInTheCurrencyItsValueWasComputedIn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("EUR", 1.25)
        runCurrent()
        val before = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single().value
        assertEquals("$2,000.00 · testnet prices", before)

        currency.choose(DisplayCurrency.EUR)
        runCurrent()
        val values = (viewModel.uiState.value as AccountsUiState.Ready).accounts.map { it.value }
        assertEquals(listOf("€1,600.00 · testnet prices"), values, "never $2,000 marked €")
    }

    @Test
    fun theHeaderTotalsEveryAccountInTheChosenCurrency() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        currency.choose(DisplayCurrency.JPY)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("500000000000000000"), 9)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("JPY", 0.0064) // USD per yen
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals("¥937,500", ready.total)
        assertEquals("Testnet prices", ready.totalNote)
        assertEquals(
            listOf("¥781,250 · testnet prices", "¥156,250 · testnet prices"),
            ready.accounts.map { it.value },
        )
    }

    @Test
    fun eachRowFollowsItsOwnAccountsBalance() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        balances.of(daily.address).value = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()

        val rows = (viewModel.uiState.value as AccountsUiState.Ready).accounts
        assertEquals(listOf("Syncing…", "Not verified"), rows.map { it.balance })
    }

    @Test
    fun aBalanceLastVerifiedIsShownDimmedAndDatedAndLeftOutOfTheTotal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        feeds.answer("ETH", 2_000.0)

        balances.of(treasury.address).value =
            BalanceReading.LastKnown(Wei.of("2500000000000000000"), 9, asOf = 1_792_100_000)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            treasuryItem("2.5 ETH", verified = false)
                .copy(balanceNote = "Last verified <1792100000> · may have changed since"),
            ready.accounts.single(),
        )
        assertEquals("Checking…", ready.total, "a balance that may have changed is not added up")
    }

    @Test
    fun eachSectionsStatusFollowsItsNetworksLightClient() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()

        assertEquals(
            LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true, NOT_VERIFIED),
            viewModel.sepoliaStatus,
        )
    }

    @Test
    fun aLightClientRefusingAStaleCheckpointIsWarnedOfAndCaughtUpOnlyOnTheUsersWord() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        lightClient.status.value = LightClientStatus.StaleAnchor(anchorAgePeriods = 6, boundPeriods = 3)
        runCurrent()

        val warning = assertNotNull(viewModel.sepoliaSection.staleAnchor)
        assertEquals(
            "Serotine's last trusted checkpoint for Sepolia is about 7 days old, older than the 3 days it can " +
                "safely catch up from on its own. Nothing on Sepolia is verified until you decide.",
            warning.banner,
        )
        assertEquals("Catch up with Sepolia anyway?", warning.title)
        assertTrue(warning.body.contains("cannot reveal your keys or move your funds by itself"), warning.body)
        assertTrue(consented.isEmpty(), "never without the user")

        viewModel.syncPastStaleAnchor(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), consented)

        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 6)
        runCurrent()
        assertNull(viewModel.sepoliaSection.staleAnchor, "the warning goes once it catches up")
        viewModel.syncPastStaleAnchor(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(listOf(Network.SEPOLIA), consented, "nothing asked of a light client that is not refusing")
    }

    @Test
    fun aSectionDatesTheHeadBlockAndKeepsItCurrent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        lightClient.status.value =
            LightClientStatus.Synced(headBlock = 11_923_074, headTimestamp = now - 3, finalized = null)
        runCurrent()
        assertEquals("Verified at block 11,923,074 · 3 s ago", viewModel.sepoliaStatus.text)

        now += 5
        advanceTimeBy(1_001)
        assertEquals("Verified at block 11,923,074 · 8 s ago", viewModel.sepoliaStatus.text)
    }

    private val AccountsViewModel.sepoliaStatus: LightClientItem
        get() = sepoliaSection.status

    private val AccountsViewModel.sepoliaSection: NetworkSection
        get() = (uiState.value as AccountsUiState.Ready).sections.single()

    @Test
    fun aNetworkThatIsOffKeepsItsAccountsListedWithTheirLastBalanceOutOfEveryTotal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        networks.on.value = emptySet()
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(treasury.address).value =
            BalanceReading.LastKnown(Wei.of("2500000000000000000"), 9, asOf = 1_792_100_000)
        balances.of(daily.address).value = BalanceReading.Unavailable(ReadUnavailable.NETWORK_OFF)
        feeds.answer("ETH", 2_000.0)
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        val section = ready.sections.single()
        assertEquals(false, section.on)
        assertEquals("Off", section.total)
        assertEquals(
            LightClientItem("Off in this wallet · nothing is checked", null, false, LightClientHealth.NOT_RUNNING),
            section.status,
        )
        assertEquals(
            listOf("2.5 ETH" to "Last verified <1792100000> · Sepolia is off", "Not checked" to "Sepolia is off"),
            section.accounts.map { it.balance to it.balanceNote },
        )
        assertEquals(listOf(null, null), section.accounts.map { it.value })
        assertNull(ready.total, "nothing on a network that is on to add up")
        val row = section.accounts[1]
        assertEquals(false, row.canSend)
        assertEquals("Sepolia", row.networkOff)

        // A setting that cannot be saved leaves it off, and says so once.
        networks.failSaves = true
        viewModel.turnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(
            "Sepolia could not be turned on: the setting cannot be saved right now. Try again, or in " +
                "Settings → Networks.",
            (viewModel.uiState.value as AccountsUiState.Ready).message,
        )
        viewModel.messageShown()
        runCurrent()
        assertEquals(null, (viewModel.uiState.value as AccountsUiState.Ready).message)

        networks.failSaves = false
        viewModel.turnOnNetwork(Network.SEPOLIA.chainId)
        runCurrent()
        assertEquals(setOf(Network.SEPOLIA), networks.on.value)
        assertEquals(true, (viewModel.uiState.value as AccountsUiState.Ready).sections.single().on)
    }

    @Test
    fun aRowShowsUpAtOnceAndSaysItIsCheckingUntilItsFirstReading() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        balances.silent += treasury.address
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val row = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals("Checking…", row.balance)
        assertEquals(false, row.balanceVerified)
    }

    @Test
    fun aStoredListThatIsNotWhatIsShownIsSaidSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        repository.list.value = AccountList.Unreadable(AccountList.Reason.NEWER_FORMAT)
        runCurrent()
        assertEquals(
            AccountsUiState.ListUnavailable(
                "Your account list was saved by a newer version of Serotine. Update the app to see it.",
            ),
            viewModel.uiState.value,
            "its own state, not an empty list",
        )

        repository.list.value = AccountList.Loaded(emptyList(), damagedListSetAside = true)
        runCurrent()
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            (viewModel.uiState.value as AccountsUiState.Ready).notice,
        )

        // Accounts added since the list was set aside: the notice stays, beside their balances.
        repository.list.value = AccountList.Loaded(listOf(treasury), damagedListSetAside = true)
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        runCurrent()
        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            ready.notice,
        )
        assertEquals(listOf("1 ETH"), ready.accounts.map { it.balance })
    }

    @Test
    fun aListThatBecomesReadableAgainShowsItsRowsWithBalances() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.list.value = AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(AccountsUiState.ListUnavailable::class, viewModel.uiState.value::class)

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        repository.list.value = AccountList.Loaded(listOf(treasury))
        runCurrent()

        val ready = viewModel.uiState.value as AccountsUiState.Ready
        assertEquals(listOf("2.5 ETH"), ready.accounts.map { it.balance })
        assertEquals(null, ready.notice)
    }

    @Test
    fun anUpgradePeersReportIsABannerAboveTheList() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(emptyList(), viewModel.uiState.value.upgrades)

        val ahead = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, now + 86_400, "0x6c1d9423", 5)
        upgrades.report.value = ahead
        runCurrent()

        assertEquals(
            listOfNotNull(UpgradeBannerItem.from(UpgradeWarning.Ahead(ahead), Network.SEPOLIA) { "<$it>" }),
            viewModel.uiState.value.upgrades,
            "Sepolia's banner alone: Ethereum's peers report nothing",
        )
        assertEquals(
            "Network upgrade ahead on Sepolia: update Serotine",
            viewModel.uiState.value.upgrades.single().title,
        )
    }

    @Test
    fun anUnreadableListStillShowsTheUpgradeBanner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        repository.list.value = AccountList.Unreadable(AccountList.Reason.READ_FAILED)
        upgrades.report.value = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, now + 86_400, "0x6c1d9423", 5)
        runCurrent()

        val state = viewModel.uiState.value
        assertIs<AccountsUiState.ListUnavailable>(state)
        assertEquals("Network upgrade ahead on Sepolia: update Serotine", state.upgrades.single().title)
    }

    @Test
    fun accountItemShowsTheChecksummedAddressShortenedForTheRow() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0xd8da6bf26964af9d7eed9e03e53415d37aa96045"),
                AccountKind.EOA,
                "Vitalik",
                AccountCustody.InKeyStore,
            ),
            BalanceReading.Syncing,
            Network.SEPOLIA,
        )
        assertEquals("0xd8dA…6045", item.shortAddress)
        assertEquals("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", item.address)
        assertEquals(Control.FULL_CONTROL, item.control)
    }

    @Test
    fun aSafeIsNeverShownWithMoreControlThanIsKnown() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0x" + "5a".repeat(20)),
                AccountKind.SAFE,
                "Family",
                AccountCustody.InKeyStore,
            ),
            null,
            Network.SEPOLIA,
        )
        assertEquals(Control.WATCH_ONLY, item.control)
    }

    @Test
    fun aWatchedAccountIsWatchOnly() {
        val item = AccountItem.from(
            Account(
                Network.SEPOLIA,
                Address.of("0x" + "ee".repeat(20)),
                AccountKind.EOA,
                "Savings",
                AccountCustody.Watched,
            ),
            null,
            Network.SEPOLIA,
        )
        assertEquals(Control.WATCH_ONLY, item.control)
        assertEquals("Checking…", item.balance)
    }

    @Test
    fun aVerifiedSafeShowsItsThresholdOfOwnersAndTheControlTheKeysHeldHereGive() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val before = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals(Control.WATCH_ONLY, before.control, "not read yet: never more than watch-only")
        assertEquals(null, before.signers)

        val other = Address.of("0x" + "ee".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, other), 1, 0, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9)
        runCurrent()

        val row = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals("1/2", row.signers)
        assertEquals("Safe", row.kindNote)
        assertEquals(Control.FULL_CONTROL, row.control, "Daily's key is held here, and one signature is enough")

        // The address no longer verifies as a Safe: said, and no more than watch-only.
        kind.value = KindReading.Verified(AddressKind.Contract, 10)
        runCurrent()
        val after = (viewModel.uiState.value as AccountsUiState.Ready).accounts.first()
        assertEquals("Not a recognised Safe", after.kindNote)
        assertEquals(Control.WATCH_ONLY, after.control)
        assertEquals(null, after.signers)
    }

    @Test
    fun anAccountNamedByAnEnsNameShowsItUntilTheNameNoLongerPointsToIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val savings = Account(
            Network.SEPOLIA,
            Address.of("0x" + "ef".repeat(20)),
            AccountKind.EOA,
            "savings.eth",
            AccountCustody.Watched,
            ensName = "savings.eth",
        )
        repository.accounts = listOf(savings)
        names.names["savings.eth"] = NameResolution.Resolved("savings.eth", savings.address, 90)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val named = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals("savings.eth", named.label)
        assertEquals(null, named.nameNote)

        // Checked again after the interval: the name points elsewhere now.
        names.names["savings.eth"] = NameResolution.Resolved("savings.eth", daily.address, 140)
        advanceTimeBy(10.minutes + 1.seconds)
        val moved = (viewModel.uiState.value as AccountsUiState.Ready).accounts.single()
        assertEquals(shortAddress(Eip55.format(savings.address)), moved.label)
        assertEquals("ENS name “savings.eth” no longer points to this address", moved.nameNote)
    }

    private class FakeAccountRepository : AccountRepository {
        val list = MutableStateFlow<AccountList>(AccountList.Loaded(emptyList()))
        var accounts: List<Account>
            get() = (list.value as AccountList.Loaded).accounts
            set(value) {
                list.value = AccountList.Loaded(value)
            }
        override fun observeAccounts(): Flow<AccountList> = list
        override suspend fun add(account: Account): AccountAdd = error("not used by this test")
        override suspend fun rename(id: AccountId, label: String): AccountChange = error("not used by this test")
        override suspend fun remove(id: AccountId): AccountChange = error("not used by this test")
        override suspend fun setCustody(id: AccountId, custody: AccountCustody): AccountChange =
            error("not used by this test")
    }

    /** Sepolia's peers report [report]; every other network's, nothing. */
    private class FakeNetworkUpgrades : NetworkUpgrades {
        val report = MutableStateFlow<NetworkUpgrade?>(null)
        override fun observeUpgrade(network: Network): Flow<NetworkUpgrade?> =
            if (network == Network.SEPOLIA) report else flowOf(null)
    }

    private object NoRecord : KnownUpgradeStore {
        override fun observe(network: Network): Flow<KnownUpgrade?> = flowOf(null)
        override suspend fun save(network: Network, record: KnownUpgrade): Boolean = error("not used by this test")
    }

    private class FakeLightClient : LightClient {
        val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
        override val datesFinalizedBlocks = true
        override fun observeStatus(network: Network): Flow<LightClientStatus> = status
    }

    private class FakeBalances : NativeBalances {
        private val readings = mutableMapOf<Address, MutableStateFlow<BalanceReading>>()

        /** Addresses whose balance stream has not emitted yet. */
        val silent = mutableSetOf<Address>()

        fun of(address: Address) = readings.getOrPut(address) { MutableStateFlow(BalanceReading.Syncing) }

        override fun observeBalance(network: Network, address: Address): Flow<BalanceReading> =
            if (address in silent) emptyFlow<BalanceReading>().onStart { awaitCancellation() } else of(address)
    }

    @Test
    fun aRefreshProvesEveryNetworkAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        assertTrue(freshness.refreshed.isEmpty(), "opening asks nothing: the list's own reads are fresh")

        viewModel.refresh()
        assertEquals(Network.entries.toList(), freshness.refreshed.toList())
    }
}
