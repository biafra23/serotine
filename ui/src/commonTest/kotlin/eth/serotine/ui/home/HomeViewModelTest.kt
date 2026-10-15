package eth.serotine.ui.home

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.domain.usecase.ObserveNativeBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val repository = FakeAccountRepository()
    private val balances = FakeBalances()
    private val lightClient = FakeLightClient()
    private val starting = LightClientItem.from(LightClientStatus.Starting, 0)
    private var now = 1_792_100_000L

    private fun viewModel() = HomeViewModel(
        ObserveAccounts(repository),
        ObserveNativeBalance(balances),
        ObserveLightClient(lightClient),
        Network.SEPOLIA,
        clock = { now },
    )

    private val treasury = Account(Address.of("0x" + "ab".repeat(20)), AccountKind.SAFE, "Treasury")
    private val daily = Account(Address.of("0x" + "cd".repeat(20)), AccountKind.EOA, "Daily")

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun loadsUntilTheFirstEmissionThenMirrorsTheAccountsWithTheirBalances() = runTest {
        // viewModelScope runs on Dispatchers.Main; route it through this test's scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()

        // Nothing collects yet: WhileSubscribed has not started the upstream.
        assertEquals(HomeUiState.Loading("Sepolia", starting), viewModel.uiState.value)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(HomeUiState.Ready("Sepolia", starting, emptyList()), viewModel.uiState.value)

        repository.accounts.value = listOf(treasury)
        runCurrent()
        assertEquals(
            HomeUiState.Ready(
                "Sepolia",
                starting,
                listOf(AccountItem("Treasury", treasury.address.hex, AccountKind.SAFE, "Syncing…", false)),
            ),
            viewModel.uiState.value,
        )

        balances.of(treasury.address).value = BalanceReading.Verified(Wei.of("2500000000000000000"), 9)
        runCurrent()
        assertEquals(
            AccountItem("Treasury", treasury.address.hex, AccountKind.SAFE, "2.5 ETH", true),
            (viewModel.uiState.value as HomeUiState.Ready).accounts.single(),
        )
    }

    @Test
    fun eachRowFollowsItsOwnAccountsBalance() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        repository.accounts.value = listOf(treasury, daily)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        balances.of(daily.address).value = BalanceReading.Unavailable(BalanceReading.Reason.UNPROVEN)
        runCurrent()

        val rows = (viewModel.uiState.value as HomeUiState.Ready).accounts
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
        repository.accounts.value = listOf(treasury)
        balances.silent += treasury.address
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val row = (viewModel.uiState.value as HomeUiState.Ready).accounts.single()
        assertEquals("Checking…", row.balance)
        assertEquals(false, row.balanceVerified)
    }

    @Test
    fun accountItemShortensTheAddressForTheRow() {
        val item = AccountItem.from(
            Account(Address.of("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"), AccountKind.EOA, "Vitalik"),
            BalanceReading.Syncing,
            Network.SEPOLIA,
        )
        assertEquals("0xd8da…6045", item.shortAddress)
        assertEquals("0xd8da6bf26964af9d7eed9e03e53415d37aa96045", item.address)
    }

    private class FakeAccountRepository : AccountRepository {
        val accounts = MutableStateFlow<List<Account>>(emptyList())
        override fun observeAccounts(): Flow<List<Account>> = accounts
        override suspend fun add(account: Account): Boolean = error("not used by this test")
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
