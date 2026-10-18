package eth.serotine.ui.addtoken

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.TokenMetadata
import eth.serotine.domain.repository.TokenSettings
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryUserTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

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

    @Test
    fun aCardLeftForAnotherAddressIsNeitherShownNorAdded() = runTest {
        // A's read does not end when it is cancelled, as a verified call in flight does not.
        val slow = object : TokenMetadata {
            override fun observe(token: Address): Flow<MetadataReading> = flow {
                if (token == Address.of(eurc)) {
                    emit(MetadataReading.Verified("EURC", "EURC", 6, 9))
                    withContext(NonCancellable) { delay(1.hours) }
                }
                awaitCancellation()
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val known = ObserveKnownTokens(ListCatalog(usdc), userTokens, metadata)
        val viewModel = AddTokenViewModel(known, ObserveTokenMetadata(slow), AddToken(userTokens, slow, known))
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.onAddressChange(eurc)
        runCurrent()
        assertTrue(viewModel.uiState.value.canAdd)

        val other = "0x" + "ab".repeat(20)
        viewModel.onAddressChange(other)
        runCurrent()

        assertEquals(TokenCard.Checking, viewModel.uiState.value.card)
        viewModel.add()
        runCurrent()
        assertEquals(TokenSettings.Loaded(emptyList(), emptySet()), userTokens.settings.value)
    }

    @Test
    fun aTokenThatCannotBeSavedIsSaid() = runTest {
        metadata.of(Address.of(eurc)).value = MetadataReading.Verified("EURC", "EURC", 6, 9)
        userTokens.failSaves = true
        val viewModel = viewModel()
        viewModel.onAddressChange(eurc)
        runCurrent()

        viewModel.add()
        runCurrent()

        assertEquals("The token could not be saved. Nothing was added.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.done)
    }
}
