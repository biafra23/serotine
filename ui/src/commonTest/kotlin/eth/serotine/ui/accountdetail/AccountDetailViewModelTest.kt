package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.Control
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AccountDetailViewModelTest {

    private val daily = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(daily)
    private val balances = FakeBalances()
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val link =
        Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink Token", 18, "link")
    private val uni = Token(Address.of("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984"), "UNI", "Uniswap", 18, "uni")
    private val catalog = FakeTokenCatalog(usdc, link, uni)
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()
    private val tokenBalances = FakeTokenBalances()

    private fun viewModel() = AccountDetailViewModel(
        daily.address,
        ObserveAccount(accounts),
        ObserveNativeBalance(balances),
        ObserveTokenHoldings(ObserveKnownTokens(catalog, userTokens, metadata), tokenBalances),
        SetTokenHidden(userTokens),
        Network.SEPOLIA,
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun showsTheAccountWithItsVerifiedBalanceAndTheBlockItWasVerifiedAt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(
            AccountDetailUiState.Ready(
                label = "Daily",
                address = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
                kind = AccountKind.EOA,
                control = Control.FULL_CONTROL,
                balance = "Syncing…",
                balanceVerified = false,
                balanceNote = null,
                assets = listOf(AssetRow("ETH", "Ether", "Syncing…", "eth", verified = false)),
                tokensNote = "Checking 3 tokens",
            ),
            viewModel.uiState.value,
        )

        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        runCurrent()
        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("1.5 ETH", ready.balance)
        assertEquals("Verified at block 9,412,118", ready.balanceNote)
    }

    @Test
    fun aRenameShowsAndARemovalEndsTheScreen() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        accounts.accounts.value = listOf(daily.copy(label = "Spending"))
        runCurrent()
        assertEquals("Spending", (viewModel.uiState.value as AccountDetailUiState.Ready).label)

        accounts.accounts.value = emptyList()
        runCurrent()
        assertEquals(AccountDetailUiState.Gone, viewModel.uiState.value)
    }

    @Test
    fun anUnreadableListIsSaidSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        accounts.unreadable.value = true
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(
            AccountDetailUiState.Unavailable("Your account list cannot be read right now. Serotine keeps trying."),
            viewModel.uiState.value,
        )
    }

    @Test
    fun heldTokensAreRowsWithTheirOwnBlockAndTheRestIsANote() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("3204100000000000000"), 9)
        // 1,250 USDC; no LINK; UNI not verified.
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 12)
        tokenBalances.of(daily.address, link.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 9)
        tokenBalances.of(daily.address, uni.address).value =
            TokenBalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        // Each row names its own block: here the token's is a later one than the native's.
        assertEquals(
            listOf(
                AssetRow("ETH", "Ether", "3.2041 ETH", "eth", verified = true, verifiedAt = "block 9"),
                AssetRow(
                    "USDC",
                    "USDC",
                    "1250 USDC",
                    "usdc",
                    verified = true,
                    verifiedAt = "block 12",
                    contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                ),
            ),
            ready.assets,
        )
        assertEquals("1 token not verified", ready.tokensNote)
    }

    @Test
    fun aContractThatIsNoTokenIsSaid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        listOf(usdc, link, uni).forEach {
            tokenBalances.of(daily.address, it.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 9)
        }
        tokenBalances.of(daily.address, uni.address).value = TokenBalanceReading.NotAToken
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("1 token did not answer as a token", ready.tokensNote)
    }

    @Test
    fun aRefusedTokenIsNotCalledUnverified() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        listOf(usdc, link, uni).forEach {
            tokenBalances.of(daily.address, it.address).value = TokenBalanceReading.Unavailable(ReadUnavailable.REFUSED)
        }
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("3 tokens cannot be read on this device", ready.tokensNote)
    }

    @Test
    fun hidingATokenTakesItsRowAway() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 12)
        runCurrent()

        viewModel.hideToken("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "USDC")
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(listOf("ETH"), ready.assets.map { it.symbol })
        assertEquals("USDC hidden. Settings → Tokens shows it again.", ready.message)
        viewModel.messageShown()
        assertEquals(null, (viewModel.uiState.value as AccountDetailUiState.Ready).message)
    }

    @Test
    fun aHideThatCannotBeSavedSaysSoAndKeepsTheRow() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 12)
        userTokens.failSaves = true
        runCurrent()

        viewModel.hideToken("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "USDC")
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(listOf("ETH", "USDC"), ready.assets.map { it.symbol })
        assertEquals("USDC could not be hidden: the token settings cannot be saved right now.", ready.message)
    }
}
