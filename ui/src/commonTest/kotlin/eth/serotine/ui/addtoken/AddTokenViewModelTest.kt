package eth.serotine.ui.addtoken

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveTokenMetadata
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddTokenViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val eurc = "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4"
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): AddTokenViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val known = ObserveKnownTokens(ListCatalog(usdc), userTokens, metadata)
        val viewModel = AddTokenViewModel(known, ObserveTokenMetadata(metadata), AddToken(userTokens, metadata, known))
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun theContractsClaimIsShownAndAddedAsIs() = runTest {
        metadata.of(Address.of(eurc)).value = MetadataReading.Verified("EURC", "EURC", 6, 9)
        val viewModel = viewModel()

        viewModel.onAddressChange(eurc)
        runCurrent()
        assertEquals(
            TokenCard.Claim(eurc, "EURC", "EURC", 6, "Read from the contract at block 9", warning = null),
            viewModel.uiState.value.card,
        )

        viewModel.add()
        runCurrent()
        assertTrue(viewModel.uiState.value.done)
        assertEquals(
            listOf(Token(Address.of(eurc), "EURC", "EURC", 6, null)),
            (userTokens.settings.value as TokenSettings.Loaded).added,
        )
    }

    @Test
    fun aContractCallingItselfAListedSymbolIsMarked() = runTest {
        metadata.of(Address.of(eurc)).value = MetadataReading.Verified("usdc", "Fake", 6, 9)
        val viewModel = viewModel()

        viewModel.onAddressChange(eurc)
        runCurrent()

        val claim = viewModel.uiState.value.card as TokenCard.Claim
        assertEquals("Not the USDC the wallet lists: another contract calls itself that", claim.warning)
    }

    @Test
    fun aKnownAddressOrANonTokenCannotBeAdded() = runTest {
        val viewModel = viewModel()
        viewModel.onAddressChange(usdc.address.hex)
        runCurrent()
        assertEquals(TokenCard.Known("USDC"), viewModel.uiState.value.card)
        assertFalse(viewModel.uiState.value.canAdd)

        metadata.of(Address.of(eurc)).value = MetadataReading.NotAToken
        viewModel.onAddressChange(eurc)
        runCurrent()
        assertEquals(TokenCard.Unavailable("This address does not answer as a token."), viewModel.uiState.value.card)
        assertFalse(viewModel.uiState.value.canAdd)
    }
}
