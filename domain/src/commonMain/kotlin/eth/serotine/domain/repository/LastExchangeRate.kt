package eth.serotine.domain.repository

import eth.serotine.domain.model.RateSnapshot

/**
 * The port for the exchange rate last verified, kept beyond the process so a home-screen widget
 * can show it without starting the light client. :data keeps it in a file. Public data only:
 * a price, its times, no account.
 */
interface LastExchangeRate {
    /** The rate last saved, or null when none is (or it cannot be read). Never throws. */
    suspend fun load(): RateSnapshot?

    /** Keeps [snapshot] in place of the last one; false when it could not be saved. Never throws. */
    suspend fun save(snapshot: RateSnapshot): Boolean
}
