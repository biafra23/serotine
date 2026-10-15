package eth.serotine.ui.home

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountAdd
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.usecase.ObserveAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun loadsUntilTheFirstEmissionThenMirrorsTheAccounts() = runTest {
        // viewModelScope runs on Dispatchers.Main; route it through this test's scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = HomeViewModel(ObserveAccounts(repository), Network.SEPOLIA)

        // Nothing collects yet: WhileSubscribed has not started the upstream.
        assertEquals(HomeUiState.Loading("Sepolia"), viewModel.uiState.value)

        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(HomeUiState.Ready("Sepolia", emptyList()), viewModel.uiState.value)

        val treasury = Account(Address.of("0x" + "ab".repeat(20)), AccountKind.SAFE, "Treasury")
        repository.accounts = listOf(treasury)
        runCurrent()
        assertEquals(
            HomeUiState.Ready("Sepolia", listOf(AccountItem("Treasury", "0x" + "ab".repeat(20), AccountKind.SAFE))),
            viewModel.uiState.value,
        )
    }

    @Test
    fun aStoredListThatIsNotWhatIsShownIsSaidSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = HomeViewModel(ObserveAccounts(repository), Network.SEPOLIA)
        backgroundScope.launch { viewModel.uiState.collect {} }

        repository.list.value = AccountList.Unreadable(AccountList.Reason.NEWER_FORMAT)
        runCurrent()
        assertEquals(
            HomeUiState.ListUnavailable(
                "Sepolia",
                "Your account list was saved by a newer version of Serotine. Update the app to see it.",
            ),
            viewModel.uiState.value,
            "its own state, not an empty list",
        )

        repository.list.value = AccountList.Loaded(emptyList(), damagedListSetAside = true)
        runCurrent()
        assertEquals(
            "Your saved account list was damaged and has been set aside. Add your accounts again.",
            (viewModel.uiState.value as HomeUiState.Ready).notice,
        )
    }

    @Test
    fun accountItemShortensTheAddressForTheRow() {
        val item = AccountItem.from(
            Account(Address.of("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"), AccountKind.EOA, "Vitalik"),
        )
        assertEquals("0xd8da…6045", item.shortAddress)
        assertEquals("0xd8da6bf26964af9d7eed9e03e53415d37aa96045", item.address)
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
    }
}
