package eth.serotine.ui.accountdetail

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.usecase.ObserveAccount
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveEnsNames
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSafeTxQueue
import eth.serotine.domain.usecase.ObserveSentToAddresses
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.SetTokenHidden
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeLogIndex
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeNames
import eth.serotine.ui.FakeRecovery
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryHistoryStarts
import eth.serotine.ui.MemorySafeTxQueue
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.activity.AccountActivity
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.shortAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModelTest {

    private val daily = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(daily)
    private val history = MemoryTransactionHistory()
    private val logIndex = FakeLogIndex()
    private val historyStarts = MemoryHistoryStarts()
    private val names = FakeNames()
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
    private val queue = MemorySafeTxQueue()
    private val recovery = FakeRecovery()
    private val kind = MutableStateFlow<KindReading>(KindReading.Syncing)
    private val kinds = object : AddressKinds {
        override fun observeKind(address: Address): Flow<KindReading> = kind
    }

    private fun viewModel(address: Address = daily.address) = AccountDetailViewModel(
        address,
        ObserveAccount(accounts),
        ObserveAccountValue(
            ObserveNativeBalance(balances),
            ObserveTokenHoldings(ObserveKnownTokens(catalog, userTokens, metadata), tokenBalances),
            ObserveUsdPrice(feeds),
            ObserveDisplayCurrencies(feeds, currency),
            Network.SEPOLIA,
        ),
        ObserveSafe(kinds, accounts),
        ObserveSafeTxQueue(queue, ObserveSafe(kinds, accounts), recovery, Network.SEPOLIA),
        ObserveKnownTokens(catalog, userTokens, metadata),
        SetTokenHidden(userTokens),
        Network.SEPOLIA,
        ObserveEnsNames(accounts, names),
        activity(),
        formatTime = { "<$it>" },
    )

    private fun activity() = AccountActivity(
        ObserveAccountHistory(history),
        ObserveAccounts(accounts),
        ObserveSafeHistory(logIndex, historyStarts),
        ObserveTokenTransfers(logIndex, historyStarts),
        ObserveKnownTokens(catalog, userTokens, metadata),
        ObserveSentToAddresses(history),
        Network.SEPOLIA,
    ) { "at $it" }

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
                pendingAssets = 3,
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
        assertEquals(
            listOf("$3,000.00 · testnet prices", "$2,500.00 · testnet prices", "No price"),
            ready.assets.map { it.value },
        )

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
        assertEquals("€2,000.00 · price 2 h old · testnet prices", ready.assets.single().value)
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
    fun aNameThatFollowsAnEnsNameGivesWayToTheAddressOnceTheNameMoves() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        names.names["daily.eth"] = NameResolution.Resolved("daily.eth", daily.address, 90)
        accounts.accounts.value = listOf(daily.copy(label = "daily.eth", ensName = "daily.eth"))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val named = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("daily.eth", named.label)
        assertEquals(null, named.nameNote)

        // The name now points nowhere: the screen opened again checks it at once.
        names.names["daily.eth"] = NameResolution.NoAddress
        val again = viewModel()
        backgroundScope.launch { again.uiState.collect {} }
        runCurrent()
        val moved = again.uiState.value as AccountDetailUiState.Ready
        assertEquals(shortAddress(Eip55.format(daily.address)), moved.label)
        assertEquals("ENS name “daily.eth” no longer points to this address", moved.nameNote)
    }

    @Test
    fun aNameThatResolvesOnlyOffchainIsSaidToBeUnverifiable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        names.names["daily.eth"] = NameResolution.OffchainOnly
        accounts.accounts.value = listOf(daily.copy(label = "daily.eth", ensName = "daily.eth"))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(shortAddress(Eip55.format(daily.address)), ready.label)
        assertEquals("ENS name “daily.eth” can no longer be verified to point to this address", ready.nameNote)
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
    fun amountsLastVerifiedAreShownDatedAndNotAsVerified() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        balances.of(daily.address).value =
            BalanceReading.LastKnown(Wei.of("3204100000000000000"), 9, asOf = 1_792_100_000)
        // 1,250 USDC last known; LINK last known to be none, so not known to be none now.
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.LastKnown(TokenUnits.of("1250000000"), 12, asOf = null)
        tokenBalances.of(daily.address, link.address).value =
            TokenBalanceReading.LastKnown(TokenUnits.of("0"), 9, asOf = 1_792_100_000)
        tokenBalances.of(daily.address, uni.address).value = TokenBalanceReading.NotAToken
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(
            listOf(
                AssetRow(
                    "ETH",
                    "Ether",
                    "3.2041 ETH",
                    "eth",
                    verified = false,
                    lastKnown = "Last verified <1792100000> · may have changed since",
                ),
                AssetRow(
                    "USDC",
                    "USDC",
                    "1250 USDC",
                    "usdc",
                    verified = false,
                    contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
                    lastKnown = "Last verified at block 12 · may have changed since",
                ),
            ),
            ready.assets,
        )
        assertEquals("Checking…", ready.total)
        assertEquals("Checking 1 token · 1 token did not answer as a token", ready.tokensNote)
        // The token last known to hold none is still checked, but holds no row's place meanwhile.
        assertEquals(0, ready.pendingAssets)
    }

    @Test
    fun eachTokenStillCheckedHoldsAPlaceUntilItsBalanceIsRead() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(3, (viewModel.uiState.value as AccountDetailUiState.Ready).pendingAssets)

        // One held: its row takes a place; one not held: it gives its place up.
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("1250000000"), 9)
        tokenBalances.of(daily.address, link.address).value = TokenBalanceReading.Verified(TokenUnits.of("0"), 9)
        runCurrent()
        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(listOf("ETH", "USDC"), ready.assets.map { it.symbol })
        assertEquals(1, ready.pendingAssets)

        tokenBalances.of(daily.address, uni.address).value = TokenBalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        runCurrent()
        assertEquals(0, (viewModel.uiState.value as AccountDetailUiState.Ready).pendingAssets)
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

    @Test
    fun aSafeShowsItsVerifiedOwnersAndTheControlTheKeysHeldHereGive() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        val carol = Address.of("0x" + "33".repeat(20))
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val reading = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(SafeDetails.Checking, reading.safe)
        assertEquals(Control.WATCH_ONLY, reading.control)

        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, carol), 2, 12, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        viewModel.show(DetailSegment.OWNERS)
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(DetailSegment.OWNERS, ready.segment)
        assertEquals("2/2", ready.threshold)
        assertEquals(Control.CO_SIGNER, ready.control, "Daily's key is held here, one of the two that must sign")
        assertEquals(
            SafeDetails.Shown(
                title = "Safe 1.4.1 · nonce 12",
                signers = "2 of 2 owners must sign",
                owners = listOf(
                    OwnerRow(Eip55.format(daily.address), "Daily", heldHere = true),
                    OwnerRow(Eip55.format(carol), label = null, heldHere = false),
                ),
                modules = emptyList(),
                guard = null,
                checkedAt = "Verified at block 9,412,118",
            ),
            ready.safe,
        )
    }

    @Test
    fun theActivityIsReadOnlyWhileItsTabIsShownAndKeptForAQuickReturn() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        fun activity() = (viewModel.uiState.value as AccountDetailUiState.Ready).activity
        // The log index's coverage is what the Activity's token transfers are read from.
        fun reading() = logIndex.coverage.subscriptionCount.value > 0

        assertEquals(ActivityUiState.Loading, activity())
        assertFalse(reading(), "Assets reads no history")

        viewModel.show(DetailSegment.ACTIVITY)
        runCurrent()
        val shown = assertIs<ActivityUiState.Shown>(activity())
        assertEquals(emptyList(), shown.rows)
        assertTrue(reading())

        viewModel.show(DetailSegment.ASSETS)
        runCurrent()
        assertEquals(ActivityUiState.Loading, activity())

        // Back within 5 s: the list as it was, not read again.
        viewModel.show(DetailSegment.ACTIVITY)
        runCurrent()
        assertEquals(shown, activity())

        viewModel.show(DetailSegment.ASSETS)
        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(reading(), "5 s after its tab was left, the history is no longer read")
    }

    @Test
    fun aSafesQueueListsItsTransactionsInNonceOrderWithTheirSignaturesAndWhatIsNotSignedHere() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        val carol = Address.of("0x" + "33".repeat(20))
        val friend = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, carol), 2, 12, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        // Queued out of order; one signed by Carol, and a delegate call nobody here signs.
        val usdcTransfer =
            SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(friend, UInt256.of(40_000_000)), 13)
        val ether = SafeTx.call(friend, UInt256.of(100_000_000_000_000_000), ByteArray(0), 12)
        val delegate = SafeTx(
            to = friend,
            value = UInt256.ZERO,
            data = ByteArray(4) { 1 },
            operation = SafeOperation.DELEGATECALL,
            safeTxGas = UInt256.ZERO,
            baseGas = UInt256.ZERO,
            gasPrice = UInt256.ZERO,
            gasToken = Address.ZERO,
            refundReceiver = Address.ZERO,
            nonce = 14,
        )
        val signed = SigningRequest.of(Network.SEPOLIA, treasury.address, usdcTransfer)
        queue.requests.value = listOf(
            signed.withSignatures(listOf(SafeSignature.Ecdsa(carol, recovery.sign(carol, signed.hash)))),
            SigningRequest.of(Network.SEPOLIA, treasury.address, ether),
            SigningRequest.of(Network.SEPOLIA, treasury.address, delegate),
            // Its nonce is passed: it is gone from the queue shown.
            SigningRequest.of(Network.SEPOLIA, treasury.address, SafeTx.call(friend, UInt256.of(1), ByteArray(0), 11)),
        )
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.show(DetailSegment.QUEUE)
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(DetailSegment.QUEUE, ready.segment)
        val rows = (ready.queue as SafeQueue.Shown).rows
        assertEquals(listOf("Send 0.1 ETH", "Send 40 USDC", "Delegate call"), rows.map { it.action })
        assertEquals(
            listOf("Nonce 12 · next", "Nonce 13 · 1 before it", "Nonce 14 · 2 before it"),
            rows.map { it.nonce },
        )
        assertEquals(
            listOf("0 of 2 signatures", "1 of 2 signatures", "0 of 2 signatures").map { "$it on this device" },
            rows.map { it.signatures },
        )
        assertEquals(listOf(null, null, "Not signed here: a delegate call"), rows.map { it.refusal })
        assertEquals("0x" + SigningRequest.of(Network.SEPOLIA, treasury.address, ether).hash.hex, rows.first().hash)
        assertEquals(Eip55.format(friend), rows.first().about)
        assertEquals(null, (ready.queue as SafeQueue.Shown).note)
        assertTrue(ready.canSend, "an owner is held here: Send proposes a Safe transaction")
    }

    @Test
    fun aFallbackHandlerThatIsNotPinnedIsSaidOnItsQueueRowAsNotSignedHere() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        val carol = Address.of("0x" + "33".repeat(20))
        val hostile = Address.of("0x" + "66".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address, carol), 2, 12, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        fun setHandler(handler: Address, nonce: Long): SafeTx {
            val data = "f08a0323" + "0".repeat(24) + handler.hex.removePrefix("0x")
            return SafeTx.call(treasury.address, UInt256.ZERO, ByteArray(data.length / 2) {
                data.substring(2 * it, 2 * it + 2).toInt(16).toByte()
            }, nonce)
        }
        queue.requests.value = listOf(
            SigningRequest.of(Network.SEPOLIA, treasury.address, setHandler(hostile, 12)),
            SigningRequest.of(
                Network.SEPOLIA,
                treasury.address,
                setHandler(PinnedFallbackHandlers.COMPATIBILITY_1_4_1, 13),
            ),
        )
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.show(DetailSegment.QUEUE)
        runCurrent()

        val rows = ((viewModel.uiState.value as AccountDetailUiState.Ready).queue as SafeQueue.Shown).rows
        // Owner ruling, 2026-10-18, #63: the pinned CompatibilityFallbackHandler is signed, any other is not.
        assertEquals(listOf("Not signed here: a fallback handler that is not pinned", null), rows.map { it.refusal })
    }

    @Test
    fun aModuleEnabledIsSaidOnItsQueueRowAsNotSignedHere() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        val module = Address.of("0x" + "66".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(daily.address), 1, 12, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        val data = "610b5925" + "0".repeat(24) + module.hex.removePrefix("0x")
        val enable = SafeTx.call(treasury.address, UInt256.ZERO, ByteArray(data.length / 2) {
            data.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }, 12)
        queue.requests.value = listOf(SigningRequest.of(Network.SEPOLIA, treasury.address, enable))
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.show(DetailSegment.QUEUE)
        runCurrent()

        val row = ((viewModel.uiState.value as AccountDetailUiState.Ready).queue as SafeQueue.Shown).rows.single()
        // Owner ruling, 2026-10-18, on #54: refused, its warning still shown.
        assertEquals("Not signed here: it enables a module", row.refusal)
        assertEquals("A module can move the Safe's funds without the owners' signatures.", row.warning)
    }

    @Test
    fun aQueueThatCannotBeReadSaysSoAndAWatchOnlySafeOffersNoSend() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        val carol = Address.of("0x" + "33".repeat(20))
        val state = SafeState("1.4.1", l2 = false, listOf(carol), 1, 3, emptyList(), guard = null)
        kind.value = KindReading.Verified(AddressKind.Safe(state), 9_412_118)
        queue.unreadable.value = true
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertIs<SafeQueue.Unavailable>(ready.queue)
        assertEquals(Control.WATCH_ONLY, ready.control)
        assertFalse(ready.canSend)
    }

    @Test
    fun anAccountThatIsNoSafeHasNoOwnersToShow() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(null, (viewModel.uiState.value as AccountDetailUiState.Ready).safe)
        assertEquals(null, (viewModel.uiState.value as AccountDetailUiState.Ready).queue)
    }

    @Test
    fun aSafesScreenShowsBeforeItsStorageIsReadAndSaysWhatItIs() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        // A read that has not answered yet: a Safe's takes a dozen proofs.
        val slow = object : AddressKinds {
            override fun observeKind(address: Address): Flow<KindReading> = flow { awaitCancellation() }
        }
        val viewModel = AccountDetailViewModel(
            treasury.address,
            ObserveAccount(accounts),
            ObserveAccountValue(
                ObserveNativeBalance(balances),
                ObserveTokenHoldings(ObserveKnownTokens(catalog, userTokens, metadata), tokenBalances),
                ObserveUsdPrice(feeds),
                ObserveDisplayCurrencies(feeds, currency),
                Network.SEPOLIA,
            ),
            ObserveSafe(slow, accounts),
            ObserveSafeTxQueue(queue, ObserveSafe(slow, accounts), recovery, Network.SEPOLIA),
            ObserveKnownTokens(catalog, userTokens, metadata),
            SetTokenHidden(userTokens),
            Network.SEPOLIA,
            ObserveEnsNames(accounts, names),
            activity(),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals(SafeDetails.Checking, ready.safe)
        assertEquals("Safe", ready.kindNote)
        assertEquals(null, ready.threshold)
    }

    @Test
    fun aSafeThatNoLongerVerifiesAsOneSaysSo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val treasury = Account(Address.of("0x" + "5a".repeat(20)), AccountKind.SAFE, "Treasury", AccountCustody.Watched)
        accounts.accounts.value = listOf(daily, treasury)
        kind.value = KindReading.Verified(AddressKind.Contract, 9)
        val viewModel = viewModel(treasury.address)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        val ready = viewModel.uiState.value as AccountDetailUiState.Ready
        assertEquals("Not a recognised Safe", ready.kindNote)
        assertEquals(Control.WATCH_ONLY, ready.control)
    }
}
