package eth.serotine.ui.assets

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
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
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObservePortfolio
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafes
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.UpgradeWarning
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.update.UpgradeBannerItem
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModelTest {

    private val repository = FakeAccountRepository()
    private val balances = FakeBalances()
    private val lightClient = FakeLightClient()
    private val upgrades = FakeNetworkUpgrades()
    private val starting = LightClientItem.from(LightClientStatus.Starting, 0)
    private var now = 1_792_100_000L
    private val feeds = FakeFeeds()
    private val kind = MutableStateFlow<KindReading>(KindReading.Syncing)
    private val kinds = object : AddressKinds {
        override fun observeKind(address: Address): Flow<KindReading> = kind
    }
    private val currency = MemoryCurrencySetting()

    private fun viewModel(): AssetsViewModel {
        val currencies = ObserveDisplayCurrencies(feeds, currency)
        val value = ObserveAccountValue(
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(
                ObserveKnownTokens(FakeTokenCatalog(), MemoryUserTokens(), FakeMetadata()),
                FakeTokenBalances(),
            ),
            ObserveUsdPrice(feeds),
            currencies,
            Network.SEPOLIA,
        )
        return AssetsViewModel(
            ObservePortfolio(ObserveAccounts(repository), value, currencies),
            ObserveSafes(repository, ObserveSafe(kinds, repository)),
            ObserveLightClient(lightClient),
            ObserveUpgradeWarning(upgrades, NoRecord, lightClient) { now },
            Network.SEPOLIA,
            clock = { now },
            formatTime = { "<$it>" },
        )
    }

    private val treasury =
        Account(Address.of("0x" + "ab".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.InKeyStore)
    private val daily = Account(Address.of("0x" + "cd".repeat(20)), AccountKind.EOA, "Daily", AccountCustody.InKeyStore)

    private fun treasuryItem(balance: String, verified: Boolean) =
        AccountItem(
            "Treasury",
            Eip55.format(treasury.address),
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
        assertEquals(AssetsUiState.Loading("Sepolia", starting), viewModel.uiState.value)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(AssetsUiState.Ready("Sepolia", starting, emptyList()), viewModel.uiState.value)

        repository.accounts = listOf(treasury)
        runCurrent()
        assertEquals(
            AssetsUiState.Ready(
                "Sepolia",
                starting,
                listOf(treasuryItem("Syncing…", verified = false)),
                total = "Checking…",
                totalNote = "Testnet prices · still checking",
            ),
            viewModel.uiState.value,
        )

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        runCurrent()
        assertEquals(
            treasuryItem("2.5 ETH", verified = true).copy(value = "Checking price…"),
            (viewModel.uiState.value as AssetsUiState.Ready).accounts.single(),
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
        val before = (viewModel.uiState.value as AssetsUiState.Ready).accounts.single().value
        assertEquals("$2,000.00 · testnet prices", before)

        currency.choose(DisplayCurrency.EUR)
        runCurrent()
        val values = (viewModel.uiState.value as AssetsUiState.Ready).accounts.map { it.value }
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

        val ready = viewModel.uiState.value as AssetsUiState.Ready
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

        val rows = (viewModel.uiState.value as AssetsUiState.Ready).accounts
        assertEquals(listOf("Syncing…", "Not verified"), rows.map { it.balance })
    }

    @Test
    fun theStatusStripFollowsTheLightClient() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        lightClient.status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()

        assertEquals(
            LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true),
            viewModel.uiState.value.lightClient,
        )
    }

    @Test
    fun theStripDatesTheHeadBlockAndKeepsItCurrent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        lightClient.status.value = LightClientStatus.Synced(headBlock = 11_923_074, headTimestamp = now - 3)
        runCurrent()
        assertEquals("Verified at block 11,923,074 · 3 s ago", viewModel.uiState.value.lightClient.text)

        now += 5
        advanceTimeBy(1_001)
        assertEquals("Verified at block 11,923,074 · 8 s ago", viewModel.uiState.value.lightClient.text)
    }

    @Test
    fun aRowShowsUpAtOnceAndSaysItIsCheckingUntilItsFirstReading() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts = listOf(treasury)
        balances.silent += treasury.address
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val row = (viewModel.uiState.value as AssetsUiState.Ready).accounts.single()
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
            AssetsUiState.ListUnavailable(
                "Sepolia",
                starting,
                "Your account list was saved by a newer version of Serotine. Update the app to see it.",
            ),
            viewModel.uiState.value,
            "its own state, not an empty list",
        )

        repository.list.value = AccountList.Loaded(emptyList(), damagedListSetAside = true)
        runCurrent()
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            (viewModel.uiState.value as AssetsUiState.Ready).notice,
        )

        // Accounts added since the list was set aside: the notice stays, beside their balances.
        repository.list.value = AccountList.Loaded(listOf(treasury), damagedListSetAside = true)
        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        runCurrent()
        val ready = viewModel.uiState.value as AssetsUiState.Ready
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
        assertEquals(AssetsUiState.ListUnavailable::class, viewModel.uiState.value::class)

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        repository.list.value = AccountList.Loaded(listOf(treasury))
        runCurrent()

        val ready = viewModel.uiState.value as AssetsUiState.Ready
        assertEquals(listOf("2.5 ETH"), ready.accounts.map { it.balance })
        assertEquals(null, ready.notice)
    }

    @Test
    fun anUpgradePeersReportIsABannerAboveTheList() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(null, viewModel.uiState.value.upgrade)

        val ahead = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, now + 86_400, "0x6c1d9423", 5)
        upgrades.report.value = ahead
        runCurrent()

        assertEquals(
            UpgradeBannerItem.from(UpgradeWarning.Ahead(ahead), Network.SEPOLIA) { "<$it>" },
            viewModel.uiState.value.upgrade,
        )
        assertEquals("Network upgrade ahead: update Serotine", viewModel.uiState.value.upgrade?.title)
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
        assertIs<AssetsUiState.ListUnavailable>(state)
        assertEquals("Network upgrade ahead: update Serotine", state.upgrade?.title)
    }

    @Test
    fun accountItemShowsTheChecksummedAddressShortenedForTheRow() {
        val item = AccountItem.from(
            Account(
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
            Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Family", AccountCustody.InKeyStore),
            null,
            Network.SEPOLIA,
        )
        assertEquals(Control.WATCH_ONLY, item.control)
    }

    @Test
    fun aWatchedAccountIsWatchOnly() {
        val item = AccountItem.from(
            Account(Address.of("0x" + "ee".repeat(20)), AccountKind.EOA, "Savings", AccountCustody.Watched),
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
        val before = (viewModel.uiState.value as AssetsUiState.Ready).accounts.first()
        assertEquals(Control.WATCH_ONLY, before.control, "not read yet: never more than watch-only")
        assertEquals(null, before.signers)

        val other = Address.of("0x" + "ee".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, other), 1, 0, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9)
        runCurrent()

        val row = (viewModel.uiState.value as AssetsUiState.Ready).accounts.first()
        assertEquals("1/2", row.signers)
        assertEquals("Safe", row.kindNote)
        assertEquals(Control.FULL_CONTROL, row.control, "Daily's key is held here, and one signature is enough")

        // The address no longer verifies as a Safe: said, and no more than watch-only.
        kind.value = KindReading.Verified(AddressKind.Contract, 10)
        runCurrent()
        val after = (viewModel.uiState.value as AssetsUiState.Ready).accounts.first()
        assertEquals("Not a recognised Safe", after.kindNote)
        assertEquals(Control.WATCH_ONLY, after.control)
        assertEquals(null, after.signers)
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
        override suspend fun rename(address: Address, label: String): AccountChange = error("not used by this test")
        override suspend fun remove(address: Address): AccountChange = error("not used by this test")
        override suspend fun setCustody(address: Address, custody: AccountCustody): AccountChange =
            error("not used by this test")
    }

    private class FakeNetworkUpgrades : NetworkUpgrades {
        val report = MutableStateFlow<NetworkUpgrade?>(null)
        override fun observeUpgrade(): Flow<NetworkUpgrade?> = report
    }

    private object NoRecord : KnownUpgradeStore {
        override fun observe(): Flow<KnownUpgrade?> = flowOf(null)
        override suspend fun save(record: KnownUpgrade): Boolean = error("not used by this test")
    }

    private class FakeLightClient : LightClient {
        val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
        override fun observeStatus(): Flow<LightClientStatus> = status
    }

    private class FakeBalances : NativeBalances {
        private val readings = mutableMapOf<Address, MutableStateFlow<BalanceReading>>()

        /** Addresses whose balance stream has not emitted yet. */
        val silent = mutableSetOf<Address>()

        fun of(address: Address) = readings.getOrPut(address) { MutableStateFlow(BalanceReading.Syncing) }

        override fun observeBalance(address: Address): Flow<BalanceReading> =
            if (address in silent) emptyFlow<BalanceReading>().onStart { awaitCancellation() } else of(address)
    }
}
