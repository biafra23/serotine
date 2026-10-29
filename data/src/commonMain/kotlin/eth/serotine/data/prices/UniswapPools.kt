package eth.serotine.data.prices

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId

/**
 * A Uniswap v3 pool pinned to price [priceId] where no Chainlink feed is pinned for it (CLAUDE.md,
 * *Data sources*: owner ruling, 2026-10-29, #205): the pool of [token] (with [tokenDecimals]) and
 * the network's WETH at [fee] (in hundredths of a basis point, as the factory names a tier), at
 * [pool], quoted in [quote]. The pin says which pool is expected; the network's pinned factory
 * proves it (`getPool` must return [pool]). [minQuoteHeld] is the floor of WETH the pool must
 * hold, in whole WETH: a thinner pool gives no price.
 */
data class PinnedPool(
    val priceId: PriceId,
    val token: Address,
    val tokenDecimals: Int,
    val fee: Int,
    val pool: Address,
    val minQuoteHeld: Long,
    val quote: PriceId = PriceId("ETH"),
)

/**
 * The pools pinned per network, each for a built-in token with no Chainlink feed there. Ethereum
 * alone: Gnosis Chain pins no Uniswap, and Sepolia has no market.
 *
 * Chosen 2026-10-29 at mainnet block 26,298,245, read through two public nodes, which agreed (a
 * debugging source only, CLAUDE.md, *Data sources*), among the pinned factory's pools of each
 * token with WETH and USDC, every fee tier:
 * - COW: the 1 % tier (≈ 2.0 M COW and 26 WETH, 76 observations reaching back ≈ 202 h). The 0.3 %
 *   tier holds more, ≈ 74 WETH, but keeps one observation, so its "average" is its spot price.
 * - SAFE: the 0.3 % tier (≈ 1.06 M SAFE and 49 WETH, 51 observations reaching back ≈ 2.8 h, so a
 *   day back is "24h —"). The 1 % tier holds 0.4 WETH.
 * The USDC pools of both hold dust. The floor, 10 WETH, keeps both and refuses a pool drained to a
 * fraction of what it holds today.
 */
object UniswapPools {

    /** The pools pinned for [network]. */
    fun of(network: Network): List<PinnedPool> = when (network) {
        Network.ETHEREUM -> ETHEREUM
        Network.GNOSIS, Network.SEPOLIA -> emptyList()
    }

    /** The averaging window: 30 minutes (owner ruling, 2026-10-29, #205). */
    const val WINDOW_SECONDS = 1_800L

    /**
     * How far the pool's price now may be from its average, in ticks: 500 ticks is about 5 %. A
     * pool further off was just moved, and its average is no price until it settles.
     */
    const val MAX_TICK_DIVERGENCE = 500L

    private const val MIN_WETH_HELD = 10L

    private val ETHEREUM = listOf(
        PinnedPool(
            PriceId("COW"),
            Address.of("0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab"),
            tokenDecimals = 18,
            fee = 10_000,
            pool = Address.of("0xfcfdfc98062d13a11cec48c44e4613eb26a34293"),
            minQuoteHeld = MIN_WETH_HELD,
        ),
        PinnedPool(
            PriceId("SAFE"),
            Address.of("0x5afe3855358e112b5647b952709e6165e1c1eeee"),
            tokenDecimals = 18,
            fee = 3_000,
            pool = Address.of("0x000ba527862e5b82cff0f7c66b646af023274aa1"),
            minQuoteHeld = MIN_WETH_HELD,
        ),
    )
}
