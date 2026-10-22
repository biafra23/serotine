package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.FeeTier
import eth.serotine.domain.model.GPv2Settlement
import eth.serotine.domain.model.GPv2Signing
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PoolKey
import eth.serotine.domain.model.QuoterV2
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SlippageTolerance
import eth.serotine.domain.model.SwapRoute
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.UniswapV3Deployment
import eth.serotine.domain.model.UniswapV3Factory
import eth.serotine.domain.model.UniswapV3Pool
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.FinalizedBlock
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CowSwapsTest {
    private val account = Address.of("0x" + "ac".repeat(20))
    private val usdcToken = Token(Address.of("0x" + "1c".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val weth = Address.of("0x" + "ff".repeat(20))
    private val wethToken = Token(weth, "WETH", "Wrapped Ether", 18, icon = null)
    private val usdc = TransferAsset.Erc20Token(usdcToken)
    private val wethAsset = TransferAsset.Erc20Token(wethToken)
    private val uniswap = UniswapV3Deployment(
        factory = Address.of("0x" + "fa".repeat(20)),
        quoterV2 = Address.of("0x" + "0e".repeat(20)),
        swapRouter02 = Address.of("0x" + "50".repeat(20)),
        weth9 = weth,
    )
    private val hundredUsdc = UInt256.of(100_000_000)
    private val headTime = 1_792_419_000L
    private val finalized = FinalizedBlock(number = 100, timestamp = headTime - 240)

    // Uniswap quotes 0.05 WETH, at 100 000 gas; at 10 gwei + 1 gwei that is 0.0011 WETH of fee.
    private val uniswapOut = UInt256.of("50000000000000000")
    private val net = UInt256.of("48900000000000000")
    private val floor = UInt256.of("48655500000000000")

    private val pools = Pools()
    private val inputs = Inputs()
    private val tokens = FakeTokenBalances()
    private val sent = MemorySentTransfers()
    private val light = FakeLightClient()
    private val starts = MemoryHistoryStarts(account to HistoryStart.Fixed(50))
    private val orders = MemoryCowOrders()
    private val cowChain = FakeCowChain()
    private val cowApi = FakeCowApi()
    private val findRoute = FindUniswapRoute(pools, inputs)
    private val quoteCow = QuoteCowSwap(
        cowChain,
        cowApi,
        findRoute,
        inputs,
        tokens,
        sent,
        orders,
        starts,
        light,
        Network.SEPOLIA,
        clock = { headTime },
    )

    init {
        tokens.of(account, usdcToken.address).value = TokenBalanceReading.Verified(TokenUnits.of("500000000"), 120)
        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime, finalized = finalized)
        pool(uniswapOut)
        allow(hundredUsdc)
        val separator = GPv2Signing.domainSeparator(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement).toHex()
        cowChain.answers[COW_SEPOLIA.settlement to GPv2Settlement.domainSeparator().toHex()] =
            Simulation.Succeeded(separator, 120)
    }

    private fun word(value: UInt256) = value.toBytes32().toHex()
    private fun word(address: Address) = "00".repeat(12) + address.hex.drop(2)

    /** One USDC/WETH pool at 0.3 %, quoting [out] for a hundred USDC at 100 000 gas. */
    private fun pool(out: UInt256) {
        val key = PoolKey.of(usdcToken.address, weth, FeeTier.MEDIUM)
        val address = Address.of("0x" + "30".repeat(20))
        pools.answers[uniswap.factory to UniswapV3Factory.getPool(key).toHex()] = word(address)
        pools.answers[address to UniswapV3Pool.liquidity().toHex()] = word(UInt256.of(1_000))
        val route = SwapRoute.direct(usdcToken.address, weth, FeeTier.MEDIUM)
        pools.answers[uniswap.quoterV2 to QuoterV2.quote(route, hundredUsdc).toHex()] =
            word(out) + "00".repeat(64) + word(UInt256.of(100_000))
    }

    private fun allow(amount: UInt256) {
        inputs.runs[usdcToken.address to Erc20.allowanceData(account, COW_SEPOLIA.vaultRelayer).toHex()] = word(amount)
    }

    private suspend fun quoteUsdcForWeth() = quoteCow(account, usdc, wethAsset, hundredUsdc, SlippageTolerance.DEFAULT)

    @Test
    fun anOrderIsBuiltHereWithItsMinimumItsValidityAndItsUid() = runTest {
        cowApi.quote = CowQuoteReading.Quoted(UInt256.of("51000000000000000"), UInt256.of(300_000))

        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        assertEquals(uniswapOut, quote.uniswapOut)
        assertEquals(UInt256.of("1100000000000000"), quote.uniswapFeeOut)
        assertEquals(net, quote.uniswapNet)
        assertEquals(floor, quote.floor)
        // CoW's 0.051 less 0.5 %, above the floor: the order keeps its whole tolerance.
        assertEquals(UInt256.of("50745000000000000"), quote.minimumOut)
        assertEquals(false, quote.belowNet)
        val order = quote.order
        assertEquals(CowOrder(usdcToken.address, weth, account, hundredUsdc, quote.minimumOut, headTime + 300), order)
        assertEquals(order.uid(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement, account), quote.uid)
        assertEquals(order.digest(Network.SEPOLIA.chainId, COW_SEPOLIA.settlement), quote.digest)
        assertEquals(finalized.number, quote.rangeStart)
        assertEquals(UInt256.of(400_000_000), quote.sellAfter)
        val asked = cowApi.quotes.single()
        assertEquals(listOf<Any>(Network.SEPOLIA, usdcToken.address, weth, hundredUsdc, account), asked)
    }

    @Test
    fun anEstimateBelowTheUniswapNetIsHeldToTheFloor() = runTest {
        cowApi.quote = CowQuoteReading.Quoted(UInt256.of("48000000000000000"), UInt256.ZERO)

        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        assertEquals(floor, quote.minimumOut)
        assertTrue(quote.belowNet)
    }

    @Test
    fun etherBoughtIsCowsMarker() = runTest {
        val quoting = quoteCow(account, usdc, TransferAsset.Native, hundredUsdc, SlippageTolerance.DEFAULT)
        val quote = assertIs<CowSwapQuoting.Quoted>(quoting).quote
        assertEquals(CowOrder.NATIVE_BUY_TOKEN, quote.order.buyToken)
    }

    @Test
    fun whatCowIsNotOfferedForNeverReachesCow() = runTest {
        assertEquals(
            CowSwapQuoting.NotOffered(CowNotOffered.SELLS_ETHER),
            quoteCow(account, TransferAsset.Native, usdc, hundredUsdc, SlippageTolerance.DEFAULT),
        )
        // No pool for the pair: no floor.
        pools.answers.clear()
        assertEquals(CowSwapQuoting.NotOffered(CowNotOffered.NO_ROUTE), quoteUsdcForWeth())
        // A route whose fee takes all it returns.
        pool(UInt256.of("1000000000000000"))
        assertEquals(CowSwapQuoting.NotOffered(CowNotOffered.FEE_ABOVE_PROCEEDS), quoteUsdcForWeth())
        assertTrue(cowApi.quotes.isEmpty())
    }

    @Test
    fun anAccountFollowedOnlyFromAfterTheFinalizedBlockIsNotOfferedCow() = runTest {
        starts.starts.value = mapOf(account to HistoryStart.Fixed(finalized.number + 1))
        assertEquals(CowSwapQuoting.NotOffered(CowNotOffered.NOT_FOLLOWED_YET), quoteUsdcForWeth())
        starts.starts.value = mapOf(account to HistoryStart.Noted(headTime))
        assertEquals(CowSwapQuoting.NotOffered(CowNotOffered.NOT_FOLLOWED_YET), quoteUsdcForWeth())
        starts.starts.value = mapOf(account to HistoryStart.Fixed(finalized.number))
        assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth())
    }

    @Test
    fun oneOrderPerSoldTokenIsLiveAtATime() = runTest {
        val first = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        val live = recordOf(first)
        orders.orders.value = listOf(live)
        assertEquals(CowSwapQuoting.EarlierOrderLive(live.uid), quoteUsdcForWeth())

        // Ended, or past its validity at a finalized block: it takes nothing more.
        orders.orders.value = listOf(live.copy(end = CowOrderEnd.Unfilled(110, cancelled = false)))
        assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth())
        orders.orders.value = listOf(live)
        light.status.value = LightClientStatus.Synced(130, headTime + 400, FinalizedBlock(110, first.order.validTo + 1))
        assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth())

        orders.unreadable = true
        assertEquals(CowSwapQuoting.OrdersUnreadable, quoteUsdcForWeth())
    }

    @Test
    fun theVaultRelayersAllowanceComesFirst() = runTest {
        allow(UInt256.of(99_999_999))
        assertEquals(
            CowSwapQuoting.NeedsApproval(usdcToken, COW_SEPOLIA.vaultRelayer, UInt256.of(99_999_999)),
            quoteUsdcForWeth(),
        )
        assertTrue(cowApi.quotes.isEmpty())
    }

    @Test
    fun aSettlementWhoseDomainSeparatorDiffersIsNoVenue() = runTest {
        cowChain.answers[COW_SEPOLIA.settlement to GPv2Settlement.domainSeparator().toHex()] =
            Simulation.Succeeded("00".repeat(32), 120)
        assertEquals(CowSwapQuoting.NoVenue(COW_SEPOLIA.settlement), quoteUsdcForWeth())
        assertTrue(cowApi.quotes.isEmpty())
    }

    @Test
    fun cowsRefusalOrSilenceIsSaid() = runTest {
        cowApi.quote = CowQuoteReading.Refused(CowRefusal.FORBIDDEN, "Forbidden")
        assertEquals(CowSwapQuoting.CowRefused(CowRefusal.FORBIDDEN, "Forbidden"), quoteUsdcForWeth())
        cowApi.quote = CowQuoteReading.Unavailable(CowUnavailable.UNDECODABLE)
        assertEquals(CowSwapQuoting.CowNoAnswer(CowUnavailable.UNDECODABLE), quoteUsdcForWeth())
    }

    @Test
    fun whatTheAccountsOwnSendsStillMoveIsNotTheOrders() = runTest {
        sent.transfers.value = listOf(
            SentTransfer(
                account = account,
                nonce = 7,
                signed = SignedTransfer(byteArrayOf(2, 7)),
                action = SentAction.Transfer(Address.of("0x" + "bb".repeat(20)), usdc, UInt256.of(450_000_000)),
                maxCost = UInt256.ONE,
                sentAt = headTime,
            ),
        )
        assertEquals(CowSwapQuoting.InsufficientToken, quoteUsdcForWeth())
    }

    @Test
    fun noFinalizedBlockGivesNoOrder() = runTest {
        light.status.value = LightClientStatus.Synced(headBlock = 120, headTimestamp = headTime)
        assertIs<CowSwapQuoting.Unavailable>(quoteUsdcForWeth())
        light.status.value = LightClientStatus.Starting
        assertEquals(CowSwapQuoting.Syncing, quoteUsdcForWeth())
    }

    private val recovery = FakeRecovery()
    private val signer = FakeSigner(recovery)
    private val place = PlaceCowOrder(findRoute, signer, orders, cowApi)
    private val unlock = Unlock.WithPassword(WalletPassword("made up"))

    private fun recordOf(quote: CowSwapQuote) = CowOrderRecord(
        account = quote.from,
        network = quote.network,
        settlement = quote.deployment.settlement,
        order = quote.order,
        uid = quote.uid,
        signature = recovery.sign(quote.from, quote.digest),
        sell = quote.sell,
        buy = quote.buy,
        signedAt = quote.headTimestamp,
        rangeStart = quote.rangeStart,
    )

    @Test
    fun anOrderIsSignedRecordedAsAttemptedThenPosted() = runTest {
        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        var recordedWhenPosted: CowPlacement? = null
        cowApi.onPlace = { recordedWhenPosted = orders.orders.value.single().placement }

        val placed = assertIs<CowOrderPlacing.Placed>(place(quote, unlock)).record
        assertEquals(account to quote.digest, signer.asked.single())
        assertEquals(CowPlacement.Attempted, recordedWhenPosted, "recorded before the POST")
        assertEquals(quote.order to quote.uid, cowApi.placed.single())
        assertEquals(CowPlacement.Accepted(quote.uid), placed.placement)
        assertEquals(listOf(placed), orders.orders.value)
    }

    @Test
    fun anOrderThatCannotBeRecordedIsNotSent() = runTest {
        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        orders.failSaves = true
        assertEquals(CowOrderPlacing.NotRecorded, place(quote, unlock))
        assertTrue(cowApi.placed.isEmpty())
    }

    @Test
    fun aFloorRisenSinceTheQuoteIsNotSigned() = runTest {
        cowApi.quote = CowQuoteReading.Quoted(UInt256.of("48000000000000000"), UInt256.ZERO)
        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        pool(UInt256.of("60000000000000000"))
        assertEquals(CowOrderPlacing.FloorRose, place(quote, unlock))
        assertTrue(signer.asked.isEmpty())
        assertTrue(orders.orders.value.isEmpty())
    }

    @Test
    fun aRefusedSignatureLeavesNothing() = runTest {
        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        signer.answer = Signing.WrongPassword
        assertEquals(CowOrderPlacing.NotSigned(Signing.WrongPassword), place(quote, unlock))
        assertTrue(orders.orders.value.isEmpty())
        assertTrue(cowApi.placed.isEmpty())
    }

    @Test
    fun cowsUnclearAnswerLeavesTheOrderLive() = runTest {
        val quote = assertIs<CowSwapQuoting.Quoted>(quoteUsdcForWeth()).quote
        cowApi.placement = CowPlacement.Unclear(CowUnavailable.TIMEOUT)
        val placed = assertIs<CowOrderPlacing.Placed>(place(quote, unlock)).record
        assertTrue(placed.live)
        assertEquals(CowPlacement.Unclear(CowUnavailable.TIMEOUT), orders.orders.value.single().placement)
    }

    /** Uniswap's pools as the test lists them; a pair not listed has no pool. */
    private inner class Pools : UniswapV3Chain {
        val answers = mutableMapOf<Pair<Address, String>, String>()

        override suspend fun venue(network: Network): VenueReading = VenueReading.Verified(uniswap)

        override suspend fun call(to: Address, data: ByteArray): Simulation {
            answers[to to data.toHex()]?.let { return Simulation.Succeeded(it, 120) }
            if (to == uniswap.factory) return Simulation.Succeeded("00".repeat(32), 120)
            return Simulation.Reverted(120)
        }
    }

    /** Nonce 7, 10 gwei and 1 gwei tips; a run answers [runs] or one word of 1. */
    private inner class Inputs : TransactionInputs {
        val runs = mutableMapOf<Pair<Address, String>, String>()

        override suspend fun nonce(account: Address): NonceReading = NonceReading.Read(7)
        override suspend fun minedNonce(account: Address): NonceReading = NonceReading.Read(7)
        override suspend fun fees(): FeeReading = FeeReading.Quoted(
            FeeQuote(
                nextBaseFee = UInt256.of(10_000_000_000L),
                tips = FeeLevel.entries.associateWith { UInt256.of(1_000_000_000L) },
                blockNumber = 119,
            ),
        )

        override suspend fun estimateGas(from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate =
            GasEstimate.Estimated(80_000)

        override suspend fun simulate(from: Address, to: Address, value: UInt256, data: ByteArray): Simulation =
            Simulation.Succeeded(runs[to to data.toHex()] ?: ("00".repeat(31) + "01"), 120)
    }
}
