package eth.serotine.domain.model

/**
 * An Ethereum network Serotine works on, with its EIP-155 [chainId] and its native
 * currency's [nativeSymbol] (the chain registry's), [nativeName], [nativeIcon] (the key of its
 * bundled icon) and [nativePriceId], the price id of its feed; [testnet] when its prices are
 * a testnet's, which have no market value (docs/wallet-ux.md, *Prices*); [secondsPerSlot], the
 * length of its beacon slot, at most one block each. Ethereum and Gnosis Chain beside Sepolia,
 * one chain per account (owner ruling, 2026-10-22; CLAUDE.md, *Data sources*;
 * docs/networks.md), in the order the screens list them; adding another is the owner's decision.
 */
enum class Network(
    val chainId: Long,
    val displayName: String,
    val nativeSymbol: String,
    val nativeName: String,
    val nativeIcon: String,
    val nativePriceId: PriceId,
    val testnet: Boolean,
    val secondsPerSlot: Long,
) {
    /** Ethereum itself, mainnet: real funds; its native currency is ether. */
    ETHEREUM(
        chainId = 1,
        displayName = "Ethereum",
        nativeSymbol = "ETH",
        nativeName = "Ether",
        nativeIcon = "eth",
        nativePriceId = PriceId("ETH"),
        testnet = false,
        secondsPerSlot = 12,
    ),

    /** Ethereum's long-lived public testnet; its native currency is (Sepolia) ether. */
    SEPOLIA(
        chainId = 11_155_111,
        displayName = "Sepolia",
        nativeSymbol = "ETH",
        nativeName = "Ether",
        nativeIcon = "eth",
        nativePriceId = PriceId("ETH"),
        testnet = true,
        secondsPerSlot = 12,
    ),
    ;

    companion object {
        /** The network whose EIP-155 chain id is [chainId], or null for one Serotine does not work on. */
        fun ofChainId(chainId: Long): Network? = entries.firstOrNull { it.chainId == chainId }
    }
}
