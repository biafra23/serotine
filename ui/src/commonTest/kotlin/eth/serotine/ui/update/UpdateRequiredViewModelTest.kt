package eth.serotine.ui.update

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.usecase.ObserveUpgradeWarning
import eth.serotine.ui.APP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class UpdateRequiredViewModelTest {

    private val report = MutableStateFlow<NetworkUpgrade?>(null)
    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Synced(100, null))
    private val activated = NetworkUpgrade(NetworkUpgrade.Phase.ACTIVE, 1_791_294_816, "0x6c1d9423", 5)

    private fun viewModel() = UpdateRequiredViewModel(
        ObserveUpgradeWarning(
            object : NetworkUpgrades {
                override fun observeUpgrade(): Flow<NetworkUpgrade?> = report
            },
            object : KnownUpgradeStore {
                override fun observe(): Flow<KnownUpgrade?> = flowOf(null)
                override suspend fun save(record: KnownUpgrade): Boolean = error("not used by this test")
            },
            object : LightClient {
                override fun observeStatus(): Flow<LightClientStatus> = status
            },
            clock = { 1_791_400_000 },
        ),
        Network.SEPOLIA,
        formatTime = { "<$it>" },
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun requiredExactlyWhileTheLightClientAgreesItStoppedVerifying() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)

        // Peers alone: the app keeps its screens, Home's banner says to update.
        report.value = activated
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)

        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 300)
        runCurrent()
        assertEquals(
            UpdateRequiredUiState.Required(
                text = "Peers report that Sepolia upgraded on <1791294816> to rules this version of Serotine does " +
                    "not support, and its light client is not verifying the chain. Update the app to keep using " +
                    "your wallet.",
                details = "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423",
                version = "This is Serotine v$APP_VERSION.",
            ),
            viewModel.uiState.value,
        )

        // A light client that verifies again: the app comes back.
        status.value = LightClientStatus.Synced(200, null)
        runCurrent()
        assertEquals(UpdateRequiredUiState.NotRequired, viewModel.uiState.value)
    }
}
