package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.Flow

/**
 * The port for what is at an address on chain — an account a key controls, one that has
 * delegated its code (EIP-7702), or a contract — as the embedded light client proves it from
 * the address's code. :data implements it over the Myotis engine (`eth.serotine.data.engine`),
 * on the one network the app works on; nothing else may answer it (CLAUDE.md, *Trust*).
 */
interface AddressKinds {
    /**
     * The kind of [address], re-emitted when the verified head moves or the light client's
     * state changes; consecutive equal readings are not repeated. A [KindReading.Verified]
     * was proven at its block; when no proof is possible the reading says why. Never fails.
     */
    fun observeKind(address: Address): Flow<KindReading>
}

/** What is at an address, as its verified code says. */
sealed interface AddressKind {

    /**
     * No code: an externally owned account, controlled by its key alone — or an address whose
     * contract is not deployed yet (a counterfactual Safe), which nothing on chain tells
     * apart until it is.
     */
    data object Eoa : AddressKind

    /**
     * An externally owned account whose code is an EIP-7702 delegation designator
     * (`0xef0100` ++ [delegate]): still controlled by its key, and also by whatever the
     * delegate's code lets act for it.
     */
    data class DelegatedEoa(val delegate: Address) : AddressKind

    /** Any other code: a contract. A Safe is one of these until Safes are detected. */
    data object Contract : AddressKind
}

/** One answer of [AddressKinds.observeKind]. */
sealed interface KindReading {

    /** The light client has no verified head yet. */
    data object Syncing : KindReading

    /** The address holds [kind] at block [blockNumber], verified by the light client. */
    data class Verified(val kind: AddressKind, val blockNumber: Long) : KindReading

    /** No verified answer can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: BalanceReading.Reason) : KindReading
}
