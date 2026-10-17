package eth.serotine.data.tokens

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Token
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The built-in token list (CLAUDE.md, *Data sources*: built in, each entry checked against
 * its contract when listed), per network. On Sepolia these are the issuers' own test
 * deployments of popular tokens that have a CC0 icon in the bundled set (the owner's choice,
 * 2026-10-17); others — EURC, WETH, PYUSD, the Aave faucet tokens — are added by address.
 *
 * Checked on 2026-10-17 with verified calls through the Myotis engine at Sepolia block
 * 11,932,144 (`symbol()`, `decimals()`, `name()`, each as listed here).
 */
object BuiltInTokens {

    /** The tokens built in for [network], in display order. */
    fun of(network: Network): List<Token> = when (network) {
        Network.SEPOLIA -> SEPOLIA
    }

    private val SEPOLIA = listOf(
        // Circle's USDC on Sepolia.
        Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, icon = "usdc"),
        // Chainlink's LINK on Sepolia.
        Token(Address.of("0x779877a7b0d9e8603169ddbd7836e478b4624789"), "LINK", "ChainLink Token", 18, icon = "link"),
        // Uniswap's UNI, at its mainnet address.
        Token(Address.of("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984"), "UNI", "Uniswap", 18, icon = "uni"),
    )
}

/** [TokenCatalog] of the built-in list of [network]; the tokens a user adds join it with the Tokens settings. */
class BuiltInTokenCatalog(private val network: Network) : TokenCatalog {
    override fun observeTokens(): Flow<List<Token>> = flowOf(BuiltInTokens.of(network))
}
