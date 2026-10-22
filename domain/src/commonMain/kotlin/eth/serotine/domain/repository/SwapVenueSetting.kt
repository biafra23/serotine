package eth.serotine.domain.repository

import kotlinx.coroutines.flow.Flow

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

/**
 * The port for the venue the user last chose on Swap, compose (22), which the next swap starts
 * at; Uniswap until the user has chosen (owner ruling, 2026-10-22, docs/swaps.md decision 10).
 * :data keeps it in a file of its own, as the display currency is kept. A convenience: a venue
 * read from here is still the user's to change before anything is signed.
 */
interface SwapVenueSetting {
    /** The choice, re-emitted on each change, or that it cannot be read. Never throws. */
    fun observe(): Flow<VenueChoice>

    /** Chooses [venue]; false when the choice could not be saved, and nothing changed. Never throws. */
    suspend fun choose(venue: SwapVenue): Boolean
}

/** What [SwapVenueSetting.observe] emits. */
sealed interface VenueChoice {
    /**
     * The [venue] last chosen, or null while none has been; [setAside] when no choice is left
     * because the stored one was damaged and set aside.
     */
    data class Chosen(val venue: SwapVenue?, val setAside: Boolean = false) : VenueChoice

    /** A choice is stored but cannot be read: a swap starts at Uniswap. */
    data object Unreadable : VenueChoice
}
