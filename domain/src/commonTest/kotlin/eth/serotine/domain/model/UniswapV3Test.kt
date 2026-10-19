package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Uniswap v3 calls against vectors made with Foundry's `cast` 1.5 (`cast sig`, `cast calldata`,
 * `cast abi-encode`), 2026-10-19: each test names the command whose output it holds. The tokens
 * are Sepolia's USDC, WETH9 and LINK; the router is the pinned SwapRouter02.
 */
class UniswapV3Test {
    private val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
    private val weth = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14")
    private val link = Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789")
    private val account = Address.of("0x70997970c51812dc3a010c7d01b50e0d17dc79c8")
    private val deployment = UniswapV3Deployment(
        factory = Address.of("0x0227628f3f023bb0b980b67d528571c95c6dac1c"),
        quoterV2 = Address.of("0xed1f6473345f45b75f8179591dd5ba1888cf2fb3"),
        swapRouter02 = Address.of("0x3bfa4769fb09eefc5a80d6e87c3b9c650f7ae48e"),
        weth9 = weth,
    )
    private val usdcToken = TransferAsset.Erc20Token(Token(usdc, "USDC", "USD Coin", 6, icon = null))
    private val linkToken = TransferAsset.Erc20Token(Token(link, "LINK", "ChainLink Token", 18, icon = null))

    private val hundredUsdc = UInt256.of(100_000_000)
    private val minimum = UInt256.of("4290000000000000000")
    private val deadline = 1_792_419_600L

    /** USDC → WETH at 0.05 %, then WETH → LINK at 0.3 %. */
    private val hop = SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.MEDIUM, link)

    @Test
    fun theSelectorsAreTheSignaturesKeccakPrefixes() {
        // cast sig '<signature>'
        mapOf(
            "getPool(address,address,uint24)" to "1698ee82",
            "liquidity()" to "1a686502",
            "slot0()" to "3850c7bd",
            "quoteExactInputSingle((address,address,uint256,uint24,uint160))" to "c6a5026a",
            "quoteExactInput(bytes,uint256)" to "cdca1753",
            "exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))" to "04e45aaf",
            "exactInput((bytes,address,uint256,uint256))" to "b858183f",
            "unwrapWETH9(uint256,address)" to "49404b7c",
            "multicall(uint256,bytes[])" to "5ae401dc",
        ).forEach { (signature, selector) -> assertEquals(selector, AbiWords.selector(signature).toHex(), signature) }
        assertEquals("1a686502", UniswapV3Pool.liquidity().toHex())
        assertEquals("3850c7bd", UniswapV3Pool.slot0().toHex())
    }

    @Test
    fun aPoolKeyHoldsItsTokensInThePoolsOrder() {
        assertEquals(PoolKey.of(usdc, weth, FeeTier.MEDIUM), PoolKey.of(weth, usdc, FeeTier.MEDIUM))
        assertEquals(usdc, PoolKey.of(weth, usdc, FeeTier.MEDIUM).token0)
        assertFailsWith<IllegalArgumentException> { PoolKey.of(usdc, usdc, FeeTier.LOW) }
    }

    @Test
    fun getPoolTakesTheSortedTokensAndTheFeeInPips() {
        // cast calldata 'getPool(address,address,uint24)' <usdc> <weth> 3000
        assertEquals(
            "1698ee82" +
                "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
                "000000000000000000000000fff9976782d46cc05630d1f6ebab18b2324d6b14" +
                "0000000000000000000000000000000000000000000000000000000000000bb8",
            UniswapV3Factory.getPool(PoolKey.of(weth, usdc, FeeTier.MEDIUM)).toHex(),
        )
    }

    @Test
    fun theFactoryAnswerIsOneAddressWord() {
        val pool = "00".repeat(12) + "ab".repeat(20)
        assertEquals(Address.of("0x" + "ab".repeat(20)), UniswapV3Factory.poolOf(pool))
        assertEquals(Address.ZERO, UniswapV3Factory.poolOf("00".repeat(32)), "no such pool")
        assertNull(UniswapV3Factory.poolOf("01" + pool.drop(2)), "bits above the address")
        assertNull(UniswapV3Factory.poolOf(pool + "00".repeat(32)), "two words")
        assertNull(UniswapV3Factory.poolOf(pool.uppercase()), "not lower-case hex")
        assertNull(UniswapV3Factory.poolOf(""), "nothing")
    }

    @Test
    fun liquidityIsAUint128Word() {
        assertEquals(UInt256.of(1_234_567), UniswapV3Pool.liquidityOf(UInt256.of(1_234_567).toBytes32().toHex()))
        assertEquals(UInt256.ZERO, UniswapV3Pool.liquidityOf("00".repeat(32)))
        assertNull(UniswapV3Pool.liquidityOf("00".repeat(15) + "01" + "00".repeat(16)), "above 128 bits")
    }

    @Test
    fun slot0IsReadForItsPriceAndItsSignedTick() {
        // cast abi-encode 'f(uint160,int24,uint16,uint16,uint16,uint8,bool)' --
        //   1461446703485210103287273052203988822378723970341 -887271 1 2 3 0 true
        // (the largest sqrt price a pool allows, and its least usable tick)
        val returned = "000000000000000000000000fffd8963efd1fc6a506488495d951d5263988d25" +
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff27619" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000003" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000001"
        assertEquals(
            PoolSlot0(UInt256.of("1461446703485210103287273052203988822378723970341"), tick = -887_271),
            UniswapV3Pool.slot0Of(returned),
        )
        val positive = "00".repeat(31) + "01" + "00".repeat(29) + "0d89e7" + "00".repeat(5 * 32)
        assertEquals(PoolSlot0(UInt256.ONE, tick = 887_271), UniswapV3Pool.slot0Of(positive))

        assertNull(UniswapV3Pool.slot0Of(returned.dropLast(64)), "six words")
        assertNull(UniswapV3Pool.slot0Of("01" + returned.drop(2)), "a price above 160 bits")
        assertNull(UniswapV3Pool.slot0Of(returned.replaceRange(64, 66, "00")), "a tick not sign-extended")
        assertNull(UniswapV3Pool.slot0Of(positive.replaceRange(64 + 56, 64 + 58, "01")), "a tick past 24 bits")
        assertNull(UniswapV3Pool.slot0Of(positive.replaceRange(64 + 58, 64 + 60, "80")), "a negative tick not extended")
    }

    @Test
    fun aRoutesPathIsItsTokensWithEachPoolsFeeBetween() {
        // cast concat-hex <usdc> 0001f4 <weth> 000bb8 <link>
        assertEquals(
            "1c7d4b196cb0c7b01d743fbc6116a902379c7238" + "0001f4" +
                "fff9976782d46cc05630d1f6ebab18b2324d6b14" + "000bb8" +
                "779877a7b0d9e8603169ddbd7836e478b4624789",
            hop.path().toHex(),
        )
        assertEquals(2, hop.pools)
        assertEquals(listOf(PoolKey.of(usdc, weth, FeeTier.LOW), PoolKey.of(weth, link, FeeTier.MEDIUM)), hop.poolKeys)
        assertEquals("002710", SwapRoute.direct(usdc, weth, FeeTier.HIGH).path().toHex().substring(40, 46), "1 %")
    }

    @Test
    fun aRouteHasOneOrTwoPoolsBetweenDifferentTokens() {
        assertFailsWith<IllegalArgumentException> { SwapRoute(listOf(usdc), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            SwapRoute(listOf(usdc, weth, link, usdc), listOf(FeeTier.LOW, FeeTier.LOW, FeeTier.LOW))
        }
        assertFailsWith<IllegalArgumentException> { SwapRoute(listOf(usdc, weth), listOf(FeeTier.LOW, FeeTier.LOW)) }
        assertFailsWith<IllegalArgumentException> { SwapRoute.direct(usdc, usdc, FeeTier.LOW) }
        assertFailsWith<IllegalArgumentException> { SwapRoute.via(usdc, FeeTier.LOW, weth, FeeTier.LOW, usdc) }
        assertFailsWith<IllegalArgumentException> { SwapRoute.via(usdc, FeeTier.LOW, usdc, FeeTier.LOW, link) }
    }

    @Test
    fun theFeeTiersAreThePipsOfUniswapsFourTiers() {
        assertEquals(listOf(100, 500, 3_000, 10_000), FeeTier.entries.map { it.pips })
        assertEquals(FeeTier.MEDIUM, FeeTier.ofPips(3_000))
        assertNull(FeeTier.ofPips(2_500))
    }

    @Test
    fun aDirectRouteIsQuotedWithQuoteExactInputSingle() {
        // cast calldata 'quoteExactInputSingle((address,address,uint256,uint24,uint160))'
        //   '(<usdc>,<link>,100000000,500,0)'
        assertEquals(
            "c6a5026a" +
                "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
                "000000000000000000000000779877a7b0d9e8603169ddbd7836e478b4624789" +
                "0000000000000000000000000000000000000000000000000000000005f5e100" +
                "00000000000000000000000000000000000000000000000000000000000001f4" +
                "0000000000000000000000000000000000000000000000000000000000000000",
            QuoterV2.quote(SwapRoute.direct(usdc, link, FeeTier.LOW), hundredUsdc).toHex(),
        )
    }

    @Test
    fun aHopIsQuotedWithQuoteExactInputAndThePath() {
        // cast calldata 'quoteExactInput(bytes,uint256)' <path> 100000000
        assertEquals(
            "cdca1753" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000005f5e100" +
                "0000000000000000000000000000000000000000000000000000000000000042" +
                "1c7d4b196cb0c7b01d743fbc6116a902379c72380001f4fff9976782d46cc056" +
                "30d1f6ebab18b2324d6b14000bb8779877a7b0d9e8603169ddbd7836e478b462" +
                "4789000000000000000000000000000000000000000000000000000000000000",
            QuoterV2.quote(hop, hundredUsdc).toHex(),
        )
    }

    @Test
    fun aSinglePoolsQuoteIsItsFourWords() {
        // cast abi-encode 'f(uint256,uint160,uint32,uint256)' 4310000000000000000 2^96 2 96000
        val returned = "0000000000000000000000000000000000000000000000003bd0322a25ef0000" +
            "0000000000000000000000000000000000000001000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000017700"
        val route = SwapRoute.direct(usdc, link, FeeTier.LOW)
        assertEquals(RouteQuote(route, UInt256.of("4310000000000000000"), 96_000), QuoterV2.quoteOf(route, returned))

        assertNull(QuoterV2.quoteOf(route, returned.dropLast(64)), "three words")
        assertNull(QuoterV2.quoteOf(route, returned.replaceRange(64, 66, "01")), "a price above 160 bits")
        assertNull(QuoterV2.quoteOf(route, returned.replaceRange(128 + 54, 128 + 56, "01")), "ticks above 32 bits")
        assertNull(QuoterV2.quoteOf(route, returned.replaceRange(192, 194, "80")), "gas beyond a Long")
        assertNull(QuoterV2.quoteOf(hop, returned), "a hop's quote has arrays")
    }

    @Test
    fun aHopsQuoteIsTheAmountTheGasAndAnEntryPerPoolInEachArray() {
        // cast abi-encode 'f(uint256,uint160[],uint32[],uint256)' 4310000000000000000
        //   '[2^96,4295128740]' '[3,0]' 182000
        val returned = "0000000000000000000000000000000000000000000000003bd0322a25ef0000" +
            "0000000000000000000000000000000000000000000000000000000000000080" +
            "00000000000000000000000000000000000000000000000000000000000000e0" +
            "000000000000000000000000000000000000000000000000000000000002c6f0" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000001000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000001000276a4" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000003" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        assertEquals(RouteQuote(hop, UInt256.of("4310000000000000000"), 182_000), QuoterV2.quoteOf(hop, returned))

        /** [returned] with byte [byte] of word [word] set to [value]. */
        fun broken(word: Int, byte: Int, value: String): String {
            val at = 64 * word + 2 * byte
            return returned.replaceRange(at, at + 2, value)
        }

        assertNull(QuoterV2.quoteOf(hop, returned.dropLast(64)), "an entry short")
        assertNull(QuoterV2.quoteOf(hop, returned + "00".repeat(32)), "a word more")
        assertNull(QuoterV2.quoteOf(hop, broken(word = 1, byte = 31, "a0")), "the prices elsewhere")
        assertNull(QuoterV2.quoteOf(hop, broken(word = 2, byte = 31, "c0")), "the ticks elsewhere")
        assertNull(QuoterV2.quoteOf(hop, broken(word = 4, byte = 31, "03")), "a length not the pools")
        assertNull(QuoterV2.quoteOf(hop, broken(word = 5, byte = 0, "01")), "a price above 160 bits")
        assertNull(QuoterV2.quoteOf(hop, broken(word = 8, byte = 27, "01")), "ticks above 32 bits")
        assertNull(QuoterV2.quoteOf(SwapRoute.direct(usdc, link, FeeTier.LOW), returned), "one pool's quote has none")
    }

    @Test
    fun aTokenSwapInOnePoolIsExactInputSingleInAMulticallWithTheDeadline() {
        // cast calldata 'multicall(uint256,bytes[])' 1792419600 "[$(cast calldata
        //   'exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))'
        //   '(<usdc>,<link>,500,<account>,100000000,4290000000000000000,0)')]"
        val call = SwapRouter02.swap(
            deployment,
            sell = usdcToken,
            buy = linkToken,
            route = SwapRoute.direct(usdc, link, FeeTier.LOW),
            amountIn = hundredUsdc,
            amountOutMinimum = minimum,
            account = account,
            deadline = deadline,
        )
        assertEquals(deployment.swapRouter02, call.to)
        assertEquals(UInt256.ZERO, call.value)
        assertEquals(
            "5ae401dc" +
                "000000000000000000000000000000000000000000000000000000006ad62710" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "00000000000000000000000000000000000000000000000000000000000000e4" +
                "04e45aaf" +
                "0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902379c7238" +
                "000000000000000000000000779877a7b0d9e8603169ddbd7836e478b4624789" +
                "00000000000000000000000000000000000000000000000000000000000001f4" +
                "00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8" +
                "0000000000000000000000000000000000000000000000000000000005f5e100" +
                "0000000000000000000000000000000000000000000000003b892445466d0000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000",
            call.data.toHex(),
        )
    }

    @Test
    fun aHopBetweenTokensIsExactInputWithThePath() {
        // cast calldata 'multicall(uint256,bytes[])' 1792419600 "[$(cast calldata
        //   'exactInput((bytes,address,uint256,uint256))' '(<path>,<account>,100000000,4290000000000000000)')]"
        val call = SwapRouter02.swap(deployment, usdcToken, linkToken, hop, hundredUsdc, minimum, account, deadline)
        assertEquals(UInt256.ZERO, call.value)
        assertEquals(
            "5ae401dc" +
                "000000000000000000000000000000000000000000000000000000006ad62710" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000124" +
                "b858183f00000000000000000000000000000000000000000000000000000000" +
                "0000002000000000000000000000000000000000000000000000000000000000" +
                "0000008000000000000000000000000070997970c51812dc3a010c7d01b50e0d" +
                "17dc79c800000000000000000000000000000000000000000000000000000000" +
                "05f5e1000000000000000000000000000000000000000000000000003b892445" +
                "466d000000000000000000000000000000000000000000000000000000000000" +
                "000000421c7d4b196cb0c7b01d743fbc6116a902379c72380001f4fff9976782" +
                "d46cc05630d1f6ebab18b2324d6b14000bb8779877a7b0d9e8603169ddbd7836" +
                "e478b46247890000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000",
            call.data.toHex(),
        )
    }

    @Test
    fun buyingEtherKeepsTheWethInTheRouterAndUnwrapsTheMinimumToTheAccount() {
        // cast calldata 'multicall(uint256,bytes[])' 1792419600 "[$(cast calldata
        //   'exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))'
        //   '(<usdc>,<weth>,500,0x…02,100000000,4290000000000000000,0)'),
        //   $(cast calldata 'unwrapWETH9(uint256,address)' 4290000000000000000 <account>)]"
        val call = SwapRouter02.swap(
            deployment,
            sell = usdcToken,
            buy = TransferAsset.Native,
            route = SwapRoute.direct(usdc, weth, FeeTier.LOW),
            amountIn = hundredUsdc,
            amountOutMinimum = minimum,
            account = account,
            deadline = deadline,
        )
        assertEquals(UInt256.ZERO, call.value)
        assertEquals(
            "5ae401dc" +
                "000000000000000000000000000000000000000000000000000000006ad62710" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000002" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000160" +
                "00000000000000000000000000000000000000000000000000000000000000e4" +
                "04e45aaf0000000000000000000000001c7d4b196cb0c7b01d743fbc6116a902" +
                "379c7238000000000000000000000000fff9976782d46cc05630d1f6ebab18b2" +
                "324d6b1400000000000000000000000000000000000000000000000000000000" +
                "000001f400000000000000000000000000000000000000000000000000000000" +
                "0000000200000000000000000000000000000000000000000000000000000000" +
                "05f5e1000000000000000000000000000000000000000000000000003b892445" +
                "466d000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000044" +
                "49404b7c0000000000000000000000000000000000000000000000003b892445" +
                "466d000000000000000000000000000070997970c51812dc3a010c7d01b50e0d" +
                "17dc79c800000000000000000000000000000000000000000000000000000000",
            call.data.toHex(),
        )
    }

    @Test
    fun sellingEtherSellsWethAndSendsTheAmountAsTheValue() {
        val call = SwapRouter02.swap(
            deployment,
            sell = TransferAsset.Native,
            buy = linkToken,
            route = SwapRoute.direct(weth, link, FeeTier.MEDIUM),
            amountIn = UInt256.of(10_000_000_000_000_000L),
            amountOutMinimum = minimum,
            account = account,
            deadline = deadline,
        )
        assertEquals(UInt256.of(10_000_000_000_000_000L), call.value)
        // Past the multicall's selector and its five head words: deadline, offset, length, offset, length.
        val swap = call.data.toHex().substring(2 * (4 + 5 * 32))
        assertEquals("04e45aaf" + "00".repeat(12) + weth.hex.drop(2), swap.take(8 + 64))
        val recipient = swap.substring(8 + 3 * 64, 8 + 4 * 64)
        assertEquals("00".repeat(12) + account.hex.drop(2), recipient, "paid to the account")
    }

    @Test
    fun aSwapIsOfTwoAssetsWithDifferentTokensEtherAgainstWethBeingAWrap() {
        val wethToken = TransferAsset.Erc20Token(Token(weth, "WETH", "Wrapped Ether", 18, icon = null))
        assertEquals(true, usdcToken.swapsWith(linkToken, deployment))
        assertEquals(true, TransferAsset.Native.swapsWith(usdcToken, deployment))
        assertEquals(false, usdcToken.swapsWith(usdcToken, deployment))
        assertEquals(false, TransferAsset.Native.swapsWith(wethToken, deployment))
        assertEquals(false, wethToken.swapsWith(TransferAsset.Native, deployment))
    }

    @Test
    fun aSwapsRouteGoesFromTheTokenSoldToTheTokenBought() {
        fun swap(sell: TransferAsset, buy: TransferAsset, route: SwapRoute) =
            SwapRouter02.swap(deployment, sell, buy, route, hundredUsdc, minimum, account, deadline)

        assertFailsWith<IllegalArgumentException> {
            swap(linkToken, usdcToken, SwapRoute.direct(usdc, link, FeeTier.LOW))
        }
        assertFailsWith<IllegalArgumentException> {
            swap(TransferAsset.Native, linkToken, SwapRoute.direct(usdc, link, FeeTier.LOW))
        }
        assertFailsWith<IllegalArgumentException> { swap(usdcToken, TransferAsset.Native, hop) }
        assertFailsWith<IllegalArgumentException> {
            SwapRouter02.swap(deployment, usdcToken, linkToken, hop, hundredUsdc, minimum, account, deadline = -1)
        }
        assertFailsWith<IllegalArgumentException>("no minimum") {
            SwapRouter02.swap(deployment, usdcToken, linkToken, hop, hundredUsdc, UInt256.ZERO, account, deadline)
        }
    }

    @Test
    fun theSwapsAmountIsReadFromTheMulticallsResultsOnly() {
        // cast abi-encode 'f(bytes[])' "[$(cast abi-encode 'f(uint256)' 4310000000000000000)]"
        val one = "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000001" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000003bd0322a25ef0000"
        // The same with unwrapWETH9's empty result after it: '[<word>,0x]'.
        val two = "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000002" +
            "0000000000000000000000000000000000000000000000000000000000000040" +
            "0000000000000000000000000000000000000000000000000000000000000080" +
            "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000003bd0322a25ef0000" +
            "0000000000000000000000000000000000000000000000000000000000000000"
        val amount = UInt256.of("4310000000000000000")
        assertEquals(amount, SwapRouter02.amountOutOf(one, buysEther = false))
        assertEquals(amount, SwapRouter02.amountOutOf(two, buysEther = true))

        assertNull(SwapRouter02.amountOutOf(one, buysEther = true), "the unwrap's result missing")
        assertNull(SwapRouter02.amountOutOf(two, buysEther = false), "a result more")
        assertNull(SwapRouter02.amountOutOf(one + "00".repeat(32), buysEther = false), "a word more")
        assertNull(SwapRouter02.amountOutOf(one.replaceRange(64 * 3 + 62, 64 * 3 + 64, "40"), false), "a longer result")
        assertNull(SwapRouter02.amountOutOf("", buysEther = false))
    }

    @Test
    fun eachSwapIsReadBackFromItsOwnCallExactly() {
        fun roundTrip(sell: TransferAsset, buy: TransferAsset, route: SwapRoute) {
            val call = SwapRouter02.swap(deployment, sell, buy, route, hundredUsdc, minimum, account, deadline)
            assertEquals(
                RouterSwap(
                    router = deployment.swapRouter02,
                    route = route,
                    sellsEther = sell == TransferAsset.Native,
                    amountIn = hundredUsdc,
                    buysEther = buy == TransferAsset.Native,
                    minimumOut = minimum,
                    deadline = deadline,
                ),
                SwapRouter02.decode(call, account),
            )
        }
        roundTrip(usdcToken, linkToken, SwapRoute.direct(usdc, link, FeeTier.LOW))
        roundTrip(usdcToken, linkToken, hop)
        roundTrip(usdcToken, TransferAsset.Native, SwapRoute.direct(usdc, weth, FeeTier.LOW))
        roundTrip(TransferAsset.Native, linkToken, SwapRoute.direct(weth, link, FeeTier.MEDIUM))
        roundTrip(linkToken, TransferAsset.Native, SwapRoute.via(link, FeeTier.MEDIUM, usdc, FeeTier.LOW, weth))
    }

    @Test
    fun aSwapPaidToAnotherOrChangedInAnyByteIsNoSwap() {
        val call = SwapRouter02.swap(deployment, usdcToken, linkToken, hop, hundredUsdc, minimum, account, deadline)
        val bob = Address.of("0x" + "bb".repeat(20))
        assertNull(SwapRouter02.decode(call, bob), "paid to the account, not to bob")
        assertNull(SwapRouter02.decode(TransactionCall(call.to, UInt256.ONE, call.data), account), "with ether")
        assertNull(SwapRouter02.decode(TransactionCall(call.to, call.value, call.data + byteArrayOf(0)), account))
        // Every byte of the call is part of the swap: none can change and still read as one.
        val data = call.data
        for (i in data.indices) {
            val changed = data.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val decoded = SwapRouter02.decode(TransactionCall(call.to, call.value, changed), account)
            if (decoded != null) {
                // Only the deadline, the amounts or the path's tokens can change into another valid swap.
                assertEquals(false, decoded == SwapRouter02.decode(call, account), "byte $i")
            }
        }
        val transfer = TransactionCall(usdc, UInt256.ZERO, Erc20.transferData(account, hundredUsdc))
        assertNull(SwapRouter02.decode(transfer, account))
    }

    @Test
    fun anyLengthOrOffsetOrDeadlineAtAllIsReadWithoutThrowing() {
        val hopCall = SwapRouter02.swap(deployment, usdcToken, linkToken, hop, hundredUsdc, minimum, account, deadline)
        val data = hopCall.data
        // Every word of the call set to 2^32 - 1, 2^31 and 2^256 - 1 in turn: a length, an offset or
        // a deadline past any bound reads as no swap, and never throws.
        for (word in 0 until (data.size - 4) / 32) {
            for (value in listOf("ffffffff", "80000000", "ff".repeat(32))) {
                val changed = data.copyOf()
                val bytes = value.chunked(2).map { it.toInt(16).toByte() }
                bytes.forEachIndexed { i, byte -> changed[4 + word * 32 + 32 - bytes.size + i] = byte }
                SwapRouter02.decode(TransactionCall(hopCall.to, hopCall.value, changed), account)
            }
        }
        val farDeadline = SwapRouter02.swap(
            deployment, usdcToken, linkToken, hop, hundredUsdc, minimum, account, SwapRouter02.MAX_DEADLINE + 1,
        )
        assertNull(SwapRouter02.decode(farDeadline, account), "past the year 9999")
        for (cut in data.indices) {
            assertNull(SwapRouter02.decode(TransactionCall(hopCall.to, hopCall.value, data.copyOf(cut)), account))
        }
    }
}
