package eth.serotine.ui.settings

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryUserTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class SettingsViewModelTest {

    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val lightClient = object : LightClient {
        override fun observeStatus(): Flow<LightClientStatus> = status
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun namesTheNetworkTheLightClientsStateAndTheVersion() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val known = ObserveKnownTokens(ListCatalog(usdc), MemoryUserTokens(), FakeMetadata())
        val currency = MemoryCurrencySetting()
        val currencies = ObserveDisplayCurrencies(FakeFeeds(), currency)
        val viewModel =
            SettingsViewModel(ObserveLightClient(lightClient), known, currencies, Network.SEPOLIA, clock = { 1_000 })
        backgroundScope.launch { viewModel.uiState.collect {} }

        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("Sepolia", state.network)
        assertEquals("Verified at block 9,412,118 · 8 s ago", state.lightClient.text)
        assertEquals(APP_VERSION, state.version)
        assertEquals("1 built in · 0 added", state.tokens)
        assertEquals("USD", state.currency)

        currency.choose(DisplayCurrency.EUR)
        runCurrent()
        assertEquals("EUR", viewModel.uiState.value.currency)

        currency.choice.value = CurrencyChoice.Unreadable
        runCurrent()
        assertEquals("USD (your choice cannot be read)", viewModel.uiState.value.currency)
    }
}
