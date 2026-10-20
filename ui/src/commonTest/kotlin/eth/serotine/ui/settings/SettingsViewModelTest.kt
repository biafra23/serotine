package eth.serotine.ui.settings

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.ConnectionCost
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.MeteredDownloadsChoice
import eth.serotine.domain.usecase.AllowHeavyDownloadsOnMetered
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveHeavyDownloads
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.APP_VERSION
import eth.serotine.ui.FakeConnectionMeter
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryMeteredDownloads
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val usdc = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USDC", 6, "usdc")
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val lightClient = object : LightClient {
        override fun observeStatus(): Flow<LightClientStatus> = status
    }

    private val meter = FakeConnectionMeter()
    private val metered = MemoryMeteredDownloads()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun viewModel(
        known: ObserveKnownTokens = ObserveKnownTokens(ListCatalog(usdc), MemoryUserTokens(), FakeMetadata()),
        currencies: ObserveDisplayCurrencies = ObserveDisplayCurrencies(FakeFeeds(), MemoryCurrencySetting()),
    ) = SettingsViewModel(
        ObserveLightClient(lightClient),
        known,
        currencies,
        ObserveHeavyDownloads(meter, metered),
        AllowHeavyDownloadsOnMetered(metered),
        Network.SEPOLIA,
        clock = { 1_000 },
    )

    @Test
    fun namesTheNetworkTheLightClientsStateAndTheVersion() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val known = ObserveKnownTokens(ListCatalog(usdc), MemoryUserTokens(), FakeMetadata())
        val currency = MemoryCurrencySetting()
        val currencies = ObserveDisplayCurrencies(FakeFeeds(), currency)
        val viewModel = viewModel(known, currencies)
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

    @Test
    fun theHeavyDownloadsAreOffOnAMeteredConnectionUntilAllowedAndSayWhenTheyArePaused() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(MeteredDownloadsItem.NOT_ALLOWED, viewModel.uiState.value.meteredDownloads)
        assertEquals(METERED_DOWNLOADS_OFF, MeteredDownloadsItem.NOT_ALLOWED.text)

        meter.cost.value = ConnectionCost.METERED
        runCurrent()
        assertEquals(
            "$METERED_DOWNLOADS_OFF · Waiting now: no unmetered connection",
            viewModel.uiState.value.meteredDownloads?.text,
        )

        viewModel.allowHeavyDownloadsOnMetered(true)
        runCurrent()
        assertEquals(MeteredDownloadsChoice.Chosen(allowed = true), metered.choice.value)
        assertEquals(
            MeteredDownloadsItem(on = true, text = METERED_DOWNLOADS_ON),
            viewModel.uiState.value.meteredDownloads,
        )
    }

    @Test
    fun aChoiceThatCouldNotBeSavedOrReadIsSaid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        metered.failSaves = true

        viewModel.allowHeavyDownloadsOnMetered(true)
        runCurrent()
        assertEquals(
            MeteredDownloadsItem(on = false, text = "$METERED_DOWNLOADS_OFF · Your choice could not be saved"),
            viewModel.uiState.value.meteredDownloads,
        )

        metered.failSaves = false
        viewModel.allowHeavyDownloadsOnMetered(true)
        runCurrent()
        assertEquals(METERED_DOWNLOADS_ON, viewModel.uiState.value.meteredDownloads?.text)

        metered.choice.value = MeteredDownloadsChoice.Unreadable
        runCurrent()
        val unreadable = assertNotNull(viewModel.uiState.value.meteredDownloads)
        assertEquals("$METERED_DOWNLOADS_OFF (your choice cannot be read)", unreadable.text)
        // Nothing can be chosen until it can be read: the switch is greyed out and says why.
        assertNotNull(unreadable.unavailableReason)
    }

    @Test
    fun onADeviceThatDoesNotReportMeteringTheSwitchIsGreyedAndSaysWhy() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        meter.cost.value = ConnectionCost.NOT_REPORTED
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val item = assertNotNull(viewModel.uiState.value.meteredDownloads)
        assertFalse(item.on)
        assertNotNull(item.unavailableReason)
        assertTrue(item.text.startsWith("This device does not say whether its connection is metered"))
    }
}
