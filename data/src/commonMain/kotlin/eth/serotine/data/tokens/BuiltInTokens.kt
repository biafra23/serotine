package eth.serotine.data.tokens

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.repository.UserTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The built-in token list (CLAUDE.md, *Data sources*: built in, each entry checked against
 * its contract when listed), per network, each with a bundled icon (`TokenIcons`). On Sepolia
 * (the owner's choices, 2026-10-17 and 2026-10-22):
 * - the issuers' own test deployments: Circle's USDC and EURC, Paxos's PYUSD, Chainlink's
 *   LINK and Uniswap's UNI, with WETH9, the wrapped ether Uniswap's list names and the swaps
 *   pin (`UniswapDeployments`);
 * - the tokens of Aave v3's Sepolia market (bgd-labs/aave-address-book, `AaveV3Sepolia`):
 *   Aave's own GHO, and the faucet tokens DAI, USDT, WBTC and AAVE, which anyone can mint and
 *   which are not their issuers', so none has a price.
 *
 * Uniswap's default list names only UNI and WETH on Sepolia; its pools there hold
 * look-alikes of most other symbols, so a token's pools do not make it the real one.
 *
 * USDC, LINK and UNI were checked on 2026-10-17 at Sepolia block 11,932,144, the rest on
 * 2026-10-22 at block 11,966,329 (USDC, LINK and UNI again with them), with verified calls
 * through the Myotis engine (`symbol()`, `decimals()`, `name()`, each as listed here).
 */
object BuiltInTokens {

    /** The tokens built in for [network], in display order. */
    fun of(network: Network): List<Token> = when (network) {
        // None built in until each is proven on mainnet (docs/networks.md, part 4).
        Network.ETHEREUM -> emptyList()
        Network.SEPOLIA -> SEPOLIA
    }

    private val SEPOLIA = listOf(
        // Circle's USDC on Sepolia.
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc", PriceId("USDC")),
        // Circle's EURC on Sepolia; no EURC feed on Sepolia, so it has no price.
        Token(Address.of("0x08210f9170f89ab7658f0b5e3ff39b0e03c594d4"), "EURC", "EURC", 6, icon = "eurc"),
        // Paxos's PYUSD on Sepolia; no PYUSD feed on Sepolia, so it has no price.
        Token(Address.of("0xcac524bca292aaade2df8a05cc58f0a65b1b3bb9"), "PYUSD", "PayPal USD", 6, icon = "pyusd"),
        // Chainlink's LINK on Sepolia.
        Token(
            Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"),
            "LINK",
            "ChainLink Token",
            18,
            "link",
            PriceId("LINK"),
        ),
        // Uniswap's UNI, at its mainnet address; Sepolia has no UNI feed, so it has no price.
        Token(Address.of("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984"), "UNI", "Uniswap", 18, icon = "uni"),
        // WETH9, one ether locked in it for each token and paid out on withdraw, hence ether's price.
        Token(
            Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14"),
            "WETH",
            "Wrapped Ether",
            18,
            "weth",
            PriceId("ETH"),
        ),
        // Aave's GHO, of its Sepolia market; no GHO feed on Sepolia, so it has no price.
        Token(Address.of("0xc4bf5cbdabe595361438f8c6a187bdc330539c60"), "GHO", "Gho Token", 18, icon = "gho"),
        // Aave's Sepolia faucet tokens: anyone can mint them, so none is priced as its namesake.
        Token(Address.of("0xff34b3d4aee8ddcd6f9afffb6fe49bd371b8a357"), "DAI", "DAI", 18, icon = "dai"),
        Token(Address.of("0xaa8e23fb1079ea71e0a56f48a2aa51851d8433d0"), "USDT", "USDT", 6, icon = "usdt"),
        Token(Address.of("0x29f2d40b0605204364af54ec677bd022da425d03"), "WBTC", "WBTC", 8, icon = "wbtc"),
        Token(Address.of("0x88541670e55cc00beefd87eb59edd1b7c511ac9a"), "AAVE", "AAVE", 18, icon = "aave"),
    )
}

/** [TokenCatalog] of each network's built-in list ([BuiltInTokens]); the tokens a user adds are [UserTokens]'. */
class BuiltInTokenCatalog : TokenCatalog {
    override fun observeTokens(network: Network): Flow<List<Token>> = flowOf(BuiltInTokens.of(network))
}
