package eth.serotine.data.safe

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeRelease
import eth.serotine.domain.repository.SafeReleases

/**
 * [SafeReleases] from the pinned deployments ([SafeDeployments]): on Sepolia, Safe 1.4.1's L1
 * singleton and SafeProxyFactory with the factory's proxy creation code, and no fallback handler.
 * Pure data; never throws.
 */
class PinnedSafeReleases : SafeReleases {
    override fun forCreation(network: Network): SafeRelease? = SafeDeployments.of(network).creation
}
