package eth.serotine.ui.lightclient

import androidx.lifecycle.viewModelScope
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.usecase.ObserveLightClient
import eth.serotine.ui.components.LightClientHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LightClientViewModelTest {

    private val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    private val lightClient = object : LightClient {
        override fun observeStatus(): Flow<LightClientStatus> = status
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun followsTheLightClientFromStartToAVerifiedHead() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = LightClientViewModel(ObserveLightClient(lightClient), clock = { 1_000 })
        runCurrent()

        assertEquals("Starting the light client…", viewModel.item.value.text)
        assertEquals(LightClientHealth.NOT_VERIFIED, viewModel.item.value.health)

        status.value = LightClientStatus.CatchingUp(done = 1, total = 4)
        runCurrent()
        assertEquals("Verifying sync committees · 1 of 4 periods", viewModel.item.value.text)
        assertEquals(0.25f, viewModel.item.value.progress)

        status.value = LightClientStatus.WaitingForHead(headAgeSeconds = 240)
        runCurrent()
        assertEquals(LightClientHealth.STALE, viewModel.item.value.health)

        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992)
        runCurrent()
        assertEquals("Verified at block 9,412,118 · 8 s ago", viewModel.item.value.text)
        assertEquals(LightClientHealth.VERIFIED, viewModel.item.value.health)
        // What the ViewModel store does when the host goes: the strip stops with it.
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun aScreenOpenedLaterFindsTheLightClientAsItIsNotStarting() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = 992)
        val viewModel = LightClientViewModel(ObserveLightClient(lightClient), clock = { 1_000 })
        runCurrent()

        // Nothing has collected it yet, as before the first screen that draws the strip opens.
        assertEquals("Verified at block 9,412,118 · 8 s ago", viewModel.item.value.text)
        // What the ViewModel store does when the host goes: the strip stops with it.
        viewModel.viewModelScope.cancel()
    }
}
