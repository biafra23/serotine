package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.flow.Flow

/**
 * The port for the transfers this wallet signed, kept across restarts: what the Send status
 * screen (docs/wallet-ux.md, screen 9) shows after the app was closed, and what keeps a new
 * transfer from reusing the nonce of one not mined yet — the engine's own record of its sends
 * lasts only while the process does. Public data only: a signed transaction's bytes are public
 * once broadcast, and no key is ever in them. :data keeps it in a file. Never throws.
 */
interface SentTransfers {

    /** Every transfer recorded, re-emitted on each change, or that the record cannot be read. */
    fun observe(): Flow<SentTransferList>

    /** Records [transfer], signed and about to be broadcast; false when it could not be saved. */
    suspend fun record(transfer: SentTransfer): Boolean

    /**
     * Forgets [account]'s transfers whose nonce is below [minedCount], its verified count of mined
     * transactions: each of those nonces is used on chain, by that transfer or another. False when
     * the record could not be saved.
     */
    suspend fun forgetMined(account: Address, minedCount: Long): Boolean

    /**
     * Forgets the transfer whose transaction hash is [hash]: one whose broadcast was rejected or
     * refused, so it can never be mined as signed and its nonce is free again. True when nothing
     * is recorded under [hash]; false when the record could not be saved.
     */
    suspend fun forget(hash: String): Boolean
}

/** What [SentTransfers.observe] holds. */
sealed interface SentTransferList {

    /** The [transfers] recorded, oldest first. */
    data class Loaded(val transfers: List<SentTransfer>) : SentTransferList

    /** The record cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : SentTransferList
}

/**
 * A transfer this wallet signed: [amount] of [asset] from [account] to [to], its transaction
 * [nonce] and [signed] bytes, the most it can take of the account's native balance ([maxCost]:
 * its value and the most its fee can be), and when it was signed ([sentAt], unix seconds).
 */
data class SentTransfer(
    val account: Address,
    val nonce: Long,
    val signed: SignedTransfer,
    val to: Address,
    val asset: TransferAsset,
    val amount: UInt256,
    val maxCost: UInt256,
    val sentAt: Long,
) {
    /** The transaction hash its receipt is watched by. */
    val hash: String
        get() = signed.hash
}
