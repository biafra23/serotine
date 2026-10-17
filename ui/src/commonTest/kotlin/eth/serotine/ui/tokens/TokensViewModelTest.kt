package eth.serotine.ui.tokens

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.RemoveToken
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryUserTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TokensViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val fake = Token(Address.of("0x" + "ee".repeat(20)), "USDC", "Not USDC", 6, null)
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): TokensViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = TokensViewModel(
            ObserveKnownTokens(ListCatalog(usdc), userTokens, metadata),
            SetTokenHidden(userTokens),
            RemoveToken(userTokens),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun eachTokenSaysWhatItsCheckSaysAndAnImpostorIsMarked() = runTest {
        userTokens.add(fake)
        val viewModel = viewModel()
        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 6, 11_932_144)
        runCurrent()

        val (builtIn, added) = viewModel.uiState.value.items
        assertEquals("Built in · checked against its contract at block 11,932,144", builtIn.status)
        assertEquals("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", builtIn.address)
        assertEquals("Added: as its contract describes itself", added.status)
        assertEquals("Not the built-in USDC: another contract", added.warning)

        metadata.of(usdc.address).value = MetadataReading.Verified("USDC", "USDC", 18, 11_932_150)
        runCurrent()
        val contradicted = viewModel.uiState.value.items.first()
        assertEquals("Not shown: its contract says USDC with 18 decimals", contradicted.status)
        assertEquals(true, contradicted.problem)
    }

    @Test
    fun aTokenIsHiddenShownAndAnAddedOneRemoved() = runTest {
        userTokens.add(fake)
        val viewModel = viewModel()

        viewModel.setHidden("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", true)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.items.first().hidden)

        viewModel.remove(fake.address.hex)
        runCurrent()
        assertEquals(1, viewModel.uiState.value.items.size)
    }

    @Test
    fun settingsThatCannotBeReadOrSavedAreSaid() = runTest {
        val viewModel = viewModel()
        userTokens.failSaves = true
        viewModel.setHidden(usdc.address.hex, true)
        runCurrent()
        assertEquals("The change could not be saved. Nothing has changed.", viewModel.uiState.value.notice)

        userTokens.settings.value = TokenSettings.Unreadable
        runCurrent()
        assertEquals(true, viewModel.uiState.value.notice?.startsWith("Your token settings cannot be read"))
    }
}
