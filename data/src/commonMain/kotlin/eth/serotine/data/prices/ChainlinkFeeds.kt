package eth.serotine.data.prices

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId

/**
 * A Chainlink feed pinned for [priceId]: its proxy [address], the price id its answer is
 * quoted in ([quote]), the [decimals] it answers with and its [heartbeatSeconds], the longest
 * it goes without an update. The pins say what is expected; the contract is the source of
 * truth, and a feed whose decimals differ from the pin is no price (docs/wallet-ux.md, *Prices*).
 */
data class PinnedFeed(
    val priceId: PriceId,
    val address: Address,
    val quote: PriceId,
    val decimals: Int,
    val heartbeatSeconds: Long,
)

/**
 * The feed table per network (CLAUDE.md, *Data sources*: Chainlink aggregators read through
 * the light client). On Sepolia these are Chainlink's own Sepolia feeds, testnet feeds: the
 * prices they give are not market values.
 *
 * Proxies, decimals and heartbeats from Chainlink's feed directory
 * (`reference-data-directory.vercel.app/feeds-ethereum-testnet-sepolia.json`), read on
 * 2026-10-17. Each feed was read the same day with verified calls through the Myotis engine at
 * Sepolia blocks 11,932,880 to 11,932,883: `description()` named the pair and `decimals()`
 * answered as pinned.
 */
object ChainlinkFeeds {

    /** The feeds pinned for [network]. */
    fun of(network: Network): List<PinnedFeed> = when (network) {
        // None pinned until each is proven on mainnet (docs/networks.md, part 4): nothing is priced.
        Network.ETHEREUM -> emptyList()
        Network.SEPOLIA -> SEPOLIA
    }

    private const val HOUR = 3_600L
    private const val DAY = 86_400L

    private val SEPOLIA = listOf(
        usd("ETH", "0x694aa1769357215de4fac081bf1f309adc325306", HOUR),
        usd("BTC", "0x1b44f3514812d835eb1bdb0acb33d3fa3351ee43", HOUR),
        usd("LINK", "0xc59e3633baac79493d908e63626716e204a45edf", HOUR),
        usd("USDC", "0xa2f78ab2355fe2f984d808b5cee7fd0a93d5270e", DAY),
        usd("EUR", "0x1a81afb8146aeffcfc5e50e8479e826e7d55b910", DAY),
        usd("GBP", "0x91fab41f5f3be955963a986366edacff1aaeaa83", DAY),
        usd("JPY", "0x8a6af2b75f23831adc973ce6288e5329f63d86c6", DAY),
    )

    /** An X / USD feed answering with 8 decimals, as every one pinned here does. */
    private fun usd(id: String, address: String, heartbeatSeconds: Long) =
        PinnedFeed(PriceId(id), Address.of(address), PriceId.USD, decimals = 8, heartbeatSeconds)
}
