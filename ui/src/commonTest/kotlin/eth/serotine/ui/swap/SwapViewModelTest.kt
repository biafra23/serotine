package eth.serotine.ui.swap

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.FindUniswapRoute
import eth.serotine.domain.usecase.ObserveAccountValue
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveNativeBalance
import eth.serotine.domain.usecase.ObserveTokenHoldings
import eth.serotine.domain.usecase.ObserveUsdPrice
import eth.serotine.domain.usecase.QuoteSwap
import eth.serotine.domain.usecase.QuoteTokenApproval
import eth.serotine.domain.usecase.RebroadcastTransfer
import eth.serotine.domain.usecase.SWAP_DEADLINE_SECONDS
import eth.serotine.domain.usecase.SendSwap
import eth.serotine.domain.usecase.SendTokenApproval
import eth.serotine.domain.usecase.WarmFeeHistory
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeBalances
import eth.serotine.ui.FakeBroadcaster
import eth.serotine.ui.FakeFeeds
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.FakeTokenBalances
import eth.serotine.ui.FakeTokenCatalog
import eth.serotine.ui.FakeTransactionInputs
import eth.serotine.ui.MemoryCurrencySetting
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemorySentTransfers
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.components.RouteStep
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/** [SwapViewModel] over fake ports, through the real use cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapViewModelTest {

    private val daily = Account(
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Daily",
        AccountCustody.InKeyStore,
    )
    private val watched = Account(
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.EOA,
        "Savings",
        AccountCustody.Watched,
    )
    private val safe = Account(
        Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb"),
        AccountKind.SAFE,
        "Treasury",
        AccountCustody.Watched,
    )
    private val usdc =
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC"))
    private val link = Token(Address.of("0x" + "77".repeat(20)), "LINK", "ChainLink Token", 18, icon = null)
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = Address.of("0x" + "ff".repeat(20)),
    )
    private val router = deployment.swapRouter02
    private val pool = Address.of("0x" + "30".repeat(20))
    private val fortyUsdc = UInt256.of(40_000_000)
    private val headTime = 1_792_419_000L

    private val accounts = FakeAccounts(daily, watched, safe)
    private val balances = FakeBalances()
    private val tokenBalances = FakeTokenBalances()
    private val inputs = FakeTransactionInputs().apply { estimate = GasEstimate.Estimated(150_000) }
    private val uniswap = PoolChain()
    private val light = FakeLightClient()
    private val signer = FakeSigner()
    private val broadcaster = FakeBroadcaster()
    private val sent = MemorySentTransfers()
    private val cowOrders = MemoryCowOrders()
    private val clock = TestTimeSource()
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)

        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")

        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport =
            error("not used by these tests")

        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")

        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")

        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(from: Address = daily.address, sell: Address? = null): SwapViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val now = { headTime }
        val viewModel = SwapViewModel(
            from,
            sell,
            ObserveAccounts(accounts),
            ObserveAccountValue(
                ObserveNativeBalance(balances),
                ObserveTokenHoldings(
                    ObserveKnownTokens(FakeTokenCatalog(usdc, link), MemoryUserTokens(), FakeMetadata()),
                    tokenBalances,
                ),
                ObserveUsdPrice(FakeFeeds()),
                ObserveDisplayCurrencies(FakeFeeds(), MemoryCurrencySetting()),
                Network.SEPOLIA,
            ),
            DescribeKeyStore(keys),
            QuoteSwap(
                FindUniswapRoute(uniswap, inputs),
                inputs,
                balances,
                tokenBalances,
                sent,
                cowOrders,
                light,
                Network.SEPOLIA,
                now,
            ),
            SendSwap(inputs, signer, broadcaster, sent, now),
            QuoteTokenApproval(inputs, balances, sent, Network.SEPOLIA, now),
            SendTokenApproval(signer, broadcaster, sent, now),
            RebroadcastTransfer(broadcaster, sent, FakeReceipts()),
            WarmFeeHistory(inputs),
            Network.SEPOLIA,
            clock,
            formatTime = { "at $it" },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val SwapViewModel.compose get() = assertIs<SwapUiState.Compose>(uiState.value).form

    init {
        balances.of(daily.address).value = BalanceReading.Verified(Wei.of("1500000000000000000"), 9_412_118)
        tokenBalances.of(daily.address, usdc.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("250000000"), 9_412_117)
        tokenBalances.of(daily.address, link.address).value =
            TokenBalanceReading.Verified(TokenUnits.of("0"), 9_412_117)
        light.status.value = LightClientStatus.Synced(headBlock = 9_412_118, headTimestamp = headTime, finalized = null)
        // One USDC–WETH pool at 0.3 %, quoting 0.016 ETH for 40 USDC.
        val key = PoolKey.of(usdc.address, deployment.weth9, FeeTier.MEDIUM)
        uniswap.answers[deployment.factory to UniswapV3Factory.getPool(key).hex()] = word(pool)
        uniswap.answers[pool to UniswapV3Pool.liquidity().hex()] = word(UInt256.of(1_000))
        // 20 040² wei per USDC unit: 40 USDC is 0.016015871808 ETH at the spot, less the 0.3 %.
        uniswap.answers[pool to UniswapV3Pool.slot0().hex()] =
            word(UInt256.of(20_040) * UInt256.of("79228162514264337593543950336")) + "00".repeat(32 * 6)
        val route = SwapRoute.direct(usdc.address, deployment.weth9, FeeTier.MEDIUM)
        uniswap.answers[deployment.quoterV2 to QuoterV2.quote(route, fortyUsdc).hex()] =
            word(UInt256.of("16000000000000000")) + "00".repeat(64) + word(UInt256.of(100_000))
        // The router's run: 0.01595 ETH, above the minimum of 0.01592; then the unwrap's empty result.
        inputs.runs[router] = Simulation.Succeeded(
            listOf(0x20L, 2L, 0x40L, 0x80L, 0x20L).joinToString("") { word(UInt256.of(it)) } +
                word(UInt256.of("15950000000000000")) + word(UInt256.ZERO),
            9_412_118,
        )
        allow(fortyUsdc)
    }

    private fun allow(amount: UInt256) {
        inputs.runs[usdc.address] = Simulation.Succeeded(word(amount), 9_412_118)
    }

    /** Composes 40 USDC for ETH and asks for the review. */
    private fun TestScope.reviewed(): SwapViewModel {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("40")
        viewModel.review()
        runCurrent()
        return viewModel
    }

    @Test
    fun composeOffersTheHeldAssetsToSellAndEveryOtherListedOneToBuy() = runTest {
        val viewModel = viewModel(sell = usdc.address)

        val form = viewModel.compose
        assertEquals(SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2"), form.from)
        assertEquals("USDC", form.sell?.symbol, "the token of the asset menu")
        assertEquals(listOf("ETH", "USDC"), form.sellOptions.map { it.symbol }, "LINK is not held")
        assertEquals(listOf("ETH", "LINK"), form.buyOptions.map { it.symbol }, "all but the one sold")
        assertNull(form.buy)
        assertEquals("0.5", form.tolerance)
        assertFalse(form.canReview, "nothing to buy yet")

        viewModel.onBuyChange(null)
        viewModel.onSellChange(null)
        assertNull(viewModel.compose.buy, "ETH for ETH is no swap: the one bought goes")
        assertEquals(listOf("USDC", "LINK"), viewModel.compose.buyOptions.map { it.symbol })
    }

    @Test
    fun aToleranceOutsideItsRangeIsSaidAndNothingIsQuoted() = runTest {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(null)
        viewModel.onAmountChange("40")
        assertTrue(viewModel.compose.canReview)

        viewModel.onToleranceChange("7")
        assertEquals("Between 0.1 % and 5 %, with at most two decimals (0.5).", viewModel.compose.toleranceError)
        assertFalse(viewModel.compose.canReview)
    }

    @Test
    fun theReviewShowsTheVerifiedRunItsMinimumRouteAndDeadline() = runTest {
        val review = assertIs<SwapUiState.SwapReview>(reviewed().uiState.value)

        assertEquals("40 USDC", review.sold)
        assertEquals("0.01595 ETH", review.receive)
        assertEquals("at least 0.01592 ETH", review.minimum)
        assertEquals("0.5 % tolerance, below QuoterV2's verified quote of 0.016 ETH", review.tolerance)
        assertEquals(
            listOf(RouteStep("USDC → WETH", "0.3 %", "0x3030303030303030303030303030303030303030")),
            review.route,
        )
        assertTrue(review.routeNote.endsWith("which the router wraps or unwraps in the same transaction."))
        assertEquals(
            SpenderItem("Uniswap's SwapRouter02", "0x5050505050505050505050505050505050505050"),
            review.spender,
        )
        assertEquals(
            "Refused by the router after at ${headTime + SWAP_DEADLINE_SECONDS}: the verified head's time and " +
                "10 minutes.",
            review.deadline,
        )
        assertEquals(listOf("USDC balance", "ETH balance"), review.balances.map { it.what })
        assertEquals("210 USDC", review.balances.first().after)
        assertNull(review.netNote, "one route, chosen as is")
        assertEquals(
            "Price impact: 0.09 %, the quote against the pools' spot prices, their fees aside.",
            review.priceImpact,
        )
        assertEquals(
            "Sepolia's pools and Chainlink's testnet feeds do not track each other: this quote is not held " +
                "against the prices the wallet shows.",
            review.priceHint,
        )
        assertEquals(
            "An earlier approval already lets the router take this much USDC: no approval is needed.",
            review.allowanceNote,
        )
    }

    @Test
    fun aSignedSwapIsSentAndRecordedAsASwap() = runTest {
        val viewModel = reviewed()
        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()

        assertIs<SwapUiState.Sent>(viewModel.uiState.value)
        assertEquals(listOf("made-up test password"), signer.passwords)
        val action = assertIs<SentAction.Swap>(sent.transfers.value.single().action)
        assertEquals(router, action.router)
        assertEquals(TransferAsset.Erc20Token(usdc), action.sell)
        assertEquals(fortyUsdc, action.amountIn)
        assertEquals(TransferAsset.Native, action.buy)
        assertEquals(UInt256.of("15920000000000000"), action.minimumOut)
    }

    @Test
    fun anAllowanceShortOfTheAmountIsApprovedFirstAndTheSwapWaitsForItsBlock() = runTest {
        allow(UInt256.of(5_000_000))
        val viewModel = reviewed()

        val approval = assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        assertEquals("40 USDC", approval.amount)
        assertEquals("5 USDC", approval.allowance)
        assertEquals("0x5050505050505050505050505050505050505050", approval.spender.address)
        assertNull(approval.resetNote)

        viewModel.onPasswordChange("made-up test password")
        viewModel.sign()
        runCurrent()
        val action = assertIs<SentAction.TokenApproval>(sent.transfers.value.single().action)
        assertEquals(fortyUsdc, action.amount)
        val compose = assertIs<SwapUiState.Compose>(viewModel.uiState.value)
        assertEquals(APPROVAL_SENT, compose.form.info)
        assertEquals("Open the approval", compose.earlier?.label)
        assertEquals("40", compose.form.amount, "the swap stays composed")

        // A fee changed meanwhile: the approval still holds the swap back, and stays said.
        viewModel.onFeeChange(FeeLevel.FAST)
        assertEquals(APPROVAL_SENT, viewModel.compose.info)
        assertEquals("Open the approval", assertIs<SwapUiState.Compose>(viewModel.uiState.value).earlier?.label)

        // Not in a block yet: Review says so again rather than ask for a second approval.
        viewModel.review()
        runCurrent()
        assertEquals(
            "An approval of this token is on its way and not in a block yet: once a block has it, Review goes on.",
            viewModel.compose.info,
        )
        assertEquals("Open the approval", assertIs<SwapUiState.Compose>(viewModel.uiState.value).earlier?.label)

        // A quote that fails before it finds the approval again keeps it said, with its link.
        light.status.value = LightClientStatus.Bootstrapping(lookingForServers = false)
        viewModel.review()
        runCurrent()
        assertEquals("The light client is not synced yet: try again in a moment.", viewModel.compose.error)
        assertEquals("Open the approval", assertIs<SwapUiState.Compose>(viewModel.uiState.value).earlier?.label)
        light.status.value = LightClientStatus.Synced(headBlock = 9_412_119, headTimestamp = headTime, finalized = null)

        // In a block, and allowed: the swap itself is quoted.
        inputs.mined = 8
        allow(fortyUsdc)
        viewModel.review()
        runCurrent()
        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
    }

    @Test
    fun anApprovalThatBecameTheSwapOnARefreshDropsThePasswordTypedForIt() = runTest {
        // One word of 1 answers both the allowance read and the approval's run (true).
        allow(UInt256.ONE)
        val viewModel = reviewed()
        assertIs<SwapUiState.ApprovalReview>(viewModel.uiState.value)
        viewModel.onPasswordChange("made-up test password")

        // Approved from elsewhere meanwhile: Refresh shows the swap, which was not read yet.
        allow(fortyUsdc)
        viewModel.refresh()
        runCurrent()

        val review = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(WalletPassword.EMPTY, review.unlock.password)
        assertFalse(review.canSign)
    }

    @Test
    fun aPairNoPoolTakesIsSaidOnTheComposeStep() = runTest {
        val viewModel = viewModel(sell = usdc.address)
        viewModel.onBuyChange(link.address.hex)
        viewModel.onAmountChange("40")
        viewModel.review()
        runCurrent()

        assertEquals(
            "No Uniswap v3 pool takes USDC for LINK, directly or through WETH, for this amount.",
            viewModel.compose.error,
        )
    }

    @Test
    fun nothingIsSwappedFromAWatchOnlyAccountOrASafe() = runTest {
        assertEquals(
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it.",
            assertIs<SwapUiState.CannotSwap>(viewModel(from = watched.address).uiState.value).message,
        )
        assertEquals(
            "A Safe's swap is proposed as Safe transactions its owners sign: Swap on the Safe's own screen " +
                "proposes one.",
            assertIs<SwapUiState.CannotSwap>(viewModel(from = safe.address).uiState.value).message,
        )
    }

    @Test
    fun thePasswordGoesWhenTheScreenIsHiddenAndTheQuoteIsMadeAgainWhenShown() = runTest {
        val viewModel = reviewed()
        viewModel.onShown()
        viewModel.onPasswordChange("made-up test password")
        val runs = uniswap.quotes

        viewModel.onHidden()
        val hidden = assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertEquals(WalletPassword.EMPTY, hidden.unlock.password)
        viewModel.onShown()
        runCurrent()

        assertTrue(uniswap.quotes > runs, "quoted again")
        assertIs<SwapUiState.SwapReview>(viewModel.uiState.value)
        assertTrue(signer.asked.isEmpty(), "nothing signed")
    }

    @Test
    fun theBiometricPromptSaysWhatIsSignedInFull() = runTest {
        biometrics = BiometricUnlock.On
        val viewModel = reviewed()
        viewModel.signWithBiometrics()
        runCurrent()

        val unlock = assertIs<Unlock.WithBiometrics>(signer.asked.single().second)
        assertEquals("Swap 40 USDC for at least 0.01592 ETH", unlock.reason)
    }

    /** Verified calls of the factory, its pool and QuoterV2 a test answers; the factory lists nothing else. */
    private inner class PoolChain : UniswapV3Chain {
        val answers = mutableMapOf<Pair<Address, String>, String>()
        var quotes = 0

        override suspend fun venue(network: Network): VenueReading = VenueReading.Verified(deployment)

        override suspend fun call(to: Address, data: ByteArray): Simulation {
            if (to == deployment.quoterV2) quotes++
            answers[to to data.hex()]?.let { return Simulation.Succeeded(it, 9_412_118) }
            if (to == deployment.factory) return Simulation.Succeeded("00".repeat(32), 9_412_118)
            return Simulation.Reverted(9_412_118)
        }
    }

    private class FakeLightClient : LightClient {
        val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)

        override fun observeStatus(): Flow<LightClientStatus> = status
    }

    private companion object {
        const val APPROVAL_SENT = "Your approval is on its way: once a block has it, Review quotes the swap."

        fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun word(value: UInt256): String = value.toBytes32().hex()

        fun word(address: Address): String = "00".repeat(12) + address.hex.drop(2)
    }
}
