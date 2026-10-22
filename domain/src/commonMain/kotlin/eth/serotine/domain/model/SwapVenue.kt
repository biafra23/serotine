package eth.serotine.domain.model

/**
 * Where a swap goes: Uniswap's contracts, verified on chain, or CoW Protocol, through CoW's
 * servers. The user decides for each swap (owner's direction, 2026-10-22; docs/swaps.md, *The
 * user's choice of venue*).
 */
enum class SwapVenue {
    /** Uniswap v3, quoted and sent through the light client alone. */
    UNISWAP,

    /** CoW Protocol: an order signed here and handed to CoW's order API. */
    COW,
}
