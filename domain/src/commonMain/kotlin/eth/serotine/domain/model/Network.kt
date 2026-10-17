package eth.serotine.domain.model

/**
 * An Ethereum network Serotine works on, with its EIP-155 [chainId] and its native
 * currency's [nativeSymbol] (the chain registry's), [nativeName] and [nativeIcon], the key of
 * its bundled icon. For now that is
 * Sepolia and nothing else (owner ruling, 2026-10-15; CLAUDE.md, *Data sources*): every
 * chain read and every transaction targets [SEPOLIA], and adding a network, mainnet
 * included, is the owner's decision.
 */
enum class Network(
    val chainId: Long,
    val displayName: String,
    val nativeSymbol: String,
    val nativeName: String,
    val nativeIcon: String,
) {
    /** Ethereum's long-lived public testnet; its native currency is (Sepolia) ether. */
    SEPOLIA(
        chainId = 11_155_111,
        displayName = "Sepolia",
        nativeSymbol = "ETH",
        nativeName = "Ether",
        nativeIcon = "eth",
    ),
}
