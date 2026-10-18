package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeRelease

/**
 * The port for the Safe release a new Safe is created from (docs/wallet-ux.md, *Safes*:
 * creation): its singleton, its proxy factory and the factory's proxy creation code, pinned per
 * network in code by :data (`eth.serotine.data.safe`). What it says is what is expected; the
 * chain is the source of truth, and a deployment is quoted only once the factory's verified run
 * agrees with it. Never throws.
 */
interface SafeReleases {

    /** The release a Safe on [network] is created from, or null when none is pinned for it. */
    fun forCreation(network: Network): SafeRelease?
}
