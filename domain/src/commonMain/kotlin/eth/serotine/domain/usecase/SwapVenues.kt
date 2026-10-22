package eth.serotine.domain.usecase

import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.VenueChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The venue a swap starts at on Swap, compose (22; docs/swaps.md, *The user's choice of venue*):
 * the one the user last chose, and Uniswap the first time, or while the stored choice cannot be
 * read (owner ruling, 2026-10-22, decision 10). Still the user's to change before anything is
 * signed.
 */
class ObserveSwapVenue(private val setting: SwapVenueSetting) {
    operator fun invoke(): Flow<SwapVenue> =
        setting.observe().map { choice -> (choice as? VenueChoice.Chosen)?.venue ?: SwapVenue.UNISWAP }
}

/** Keeps [SwapVenue] as the one the next swap starts at; false when it could not be saved. */
class ChooseSwapVenue(private val setting: SwapVenueSetting) {
    suspend operator fun invoke(venue: SwapVenue): Boolean = setting.choose(venue)
}

/**
 * Whether a CoW order can be offered on this device at all ([LightClient.datesFinalizedBlocks]), so
 * that Swap can grey CoW out before anything is asked (docs/swaps.md, *The user's choice of venue*).
 */
class CowOfferedOnThisDevice(private val lightClient: LightClient) {
    operator fun invoke(): Boolean = lightClient.datesFinalizedBlocks
}
