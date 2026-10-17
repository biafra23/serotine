package eth.serotine.domain.repository

import eth.serotine.domain.model.Address

/**
 * The port for ENS names (docs/wallet-ux.md, screen 7): a name's address, resolved by the
 * embedded engine against beacon-finalized state and nothing fresher, since the freshest state
 * would be a peer's claim (CLAUDE.md, *Trust*). :data implements it over the Myotis engine.
 * Blocks on the engine, bounded by it; never throws.
 */
interface NameResolver {
    /** The address [name] (as typed: `vitalik.eth`) points to. */
    suspend fun resolve(name: String): NameResolution
}

/** The outcome of [NameResolver.resolve]. */
sealed interface NameResolution {

    /** [name] points to [address] in the finalized state of [blockNumber]. */
    data class Resolved(val name: String, val address: Address, val blockNumber: Long) : NameResolution

    /** The name exists without an address record, or does not exist: a verified answer. */
    data object NoAddress : NameResolution

    /** [name] is not an ENS name this resolver can look up (empty labels, characters ENS refuses). */
    data object NotAName : NameResolution

    /** No verified answer right now (finalized state not served, the light client not synced). */
    data class Unavailable(val reason: ReadUnavailable) : NameResolution
}
