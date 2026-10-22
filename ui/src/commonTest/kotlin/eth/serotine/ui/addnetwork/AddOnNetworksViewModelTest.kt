package eth.serotine.ui.addnetwork

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.usecase.AddOnNetworks
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.WalletChanges
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.MemoryNetworkSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [AddOnNetworksViewModel] with Sepolia the only network there is yet: an account on it has no other
 * network to be added on, until parts 4 and 5 of docs/networks.md bring Ethereum and Gnosis Chain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddOnNetworksViewModelTest {

    private val main =
        Account(Network.SEPOLIA, Address.of("0x" + "11".repeat(20)), AccountKind.EOA, "Main", AccountCustody.InKeyStore)
    private val safe =
        Account(Network.SEPOLIA, Address.of("0x" + "22".repeat(20)), AccountKind.SAFE, "Club", AccountCustody.Watched)
    private val accounts = FakeAccounts(main, safe)
    private val networks = MemoryNetworkSettings()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(account: Account): AddOnNetworksViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = AddOnNetworksViewModel(
            account.id,
            ObserveAccounts(accounts),
            ObserveEnabledNetworks(networks),
            SetNetworkEnabled(networks),
            AddOnNetworks(accounts, WalletChanges()),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun anAccountOnEveryNetworkHasNoneToChoose() = runTest {
        val viewModel = viewModel(main)

        assertEquals(AddOnNetworksUiState.Choose("Main", emptyList()), viewModel.uiState.value)
        viewModel.add()
        runCurrent()
        assertEquals(listOf(main, safe), accounts.accounts.value, "nothing added")
    }

    @Test
    fun aSafeIsNeverAddedOnAnotherNetwork() = runTest {
        val viewModel = viewModel(safe)

        val unavailable = viewModel.uiState.value as AddOnNetworksUiState.Unavailable
        assertEquals("A Safe or another contract exists only on the network it was deployed on.", unavailable.message)
    }

    @Test
    fun aRemovedAccountEndsTheDialog() = runTest {
        val viewModel = viewModel(main)

        accounts.accounts.value = listOf(safe)
        runCurrent()

        assertEquals(AddOnNetworksUiState.Gone, viewModel.uiState.value)
    }
}
