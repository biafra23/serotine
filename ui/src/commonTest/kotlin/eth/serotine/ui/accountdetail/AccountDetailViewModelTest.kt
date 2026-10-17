package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.MemoryCurrencySetting
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
    private val usdc =
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC"))
    private val link =
        Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink Token", 18, "link")
    private val uni = Token(Address.of("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984"), "UNI", "Uniswap", 18, "uni")
    private val catalog = FakeTokenCatalog(usdc, link, uni)
    private val userTokens = MemoryUserTokens()
    private val metadata = FakeMetadata()
    private val tokenBalances = FakeTokenBalances()
    private val feeds = FakeFeeds()
    private val currency = MemoryCurrencySetting()

    private fun viewModel() = AccountDetailViewModel(
        daily.address,
        ObserveAccount(accounts),
        ObserveAccountValue(
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(ObserveKnownTokens(catalog, userTokens, metadata), tokenBalances),
            ObserveUsdPrice(feeds),
            ObserveDisplayCurrencies(feeds, currency),
            Network.SEPOLIA,
        ),
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
                total = "Checking…",
                totalNote = "Testnet prices · still checking",
                assets = listOf(AssetRow("ETH", "Ether", "Syncing…", "eth", verified = false)),
                tokensNote = "Checking 3 tokens",
                pricesNote = "Prices: Chainlink feeds, read through the light client, testnet prices",
            ),
            viewModel.uiState.value,
        )

        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        runCurrent()
        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(
            AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, "block 9,412,118", value = "Checking price…"),
            ready.assets.single(),
        )
    }

    @Test
    fun eachRowHasItsValueAndTheTotalAddsThePricedOnes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9)
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("2500000000"), 9)
        tokenBalances.of(daily.address, link.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 9)
        tokenBalances.of(daily.address, uni.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("2000000000000000000"), 9)
        feeds.answer("ETH", 2_000.0)
        feeds.answer("USDC", 1.0)
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("$5,500.00", ready.total)
        assertEquals("Testnet prices · 1 asset without a price", ready.totalNote) // UNI has no feed here
        // By value: ETH (3,000), USDC (2,500), then UNI, which has no price.
        assertEquals(listOf("ETH", "USDC", "UNI"), ready.assets.map { it.symbol })
        assertEquals(listOf("$3,000.00", "$2,500.00", "No price"), ready.assets.map { it.value })

        viewModel.sortBy(AssetSort.NAME)
        runCurrent()
        val byName = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(listOf("ETH", "UNI", "USDC"), byName.assets.map { it.symbol })
    }

    @Test
    fun aStalePriceIsShownWithItsAgeAndLeftOutOfTheTotalInTheChosenCurrency() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        currency.choose(DisplayCurrency.EUR)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1000000000000000000"), 9)
        listOf(usdc, link, uni).forEach {
            tokenBalances.of(daily.address, it.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 9)
        }
        feeds.answer("ETH", 2_500.0, ageSeconds = 7_200, stale = true)
        feeds.answer("EUR", 1.25)
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("€0.00", ready.total)
        assertEquals("Testnet prices · 1 asset without a price", ready.totalNote)
        assertEquals("€2,000.00 · price 2 h old", ready.assets.single().value)
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
                AssetRow("ETH", "Ether", "3.2041 ETH", "eth", verified = true, "block 9", "Checking price…"),
                AssetRow(
                    "USDC",
                    "USDC",
                    "1250 USDC",
                    "usdc",
                    verified = true,
                    verifiedAt = "block 12",
                    value = "Checking price…",
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
