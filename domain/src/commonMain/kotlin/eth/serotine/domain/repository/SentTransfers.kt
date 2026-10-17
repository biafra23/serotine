package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import kotlinx.coroutines.flow.Flow

/**
 * The port for the transactions this wallet sent (signed, and taken by a peer) — its transfers,
 * and its approvals and executions of Safe transactions — kept across restarts: what the Send
 * status screen (docs/wallet-ux.md, screen 9) shows, and what keeps a new transaction from
 * reusing the nonce of one not mined yet — the engine's own record of its sends lasts only while
 * the process does. Public data only: a signed transaction's bytes are public once broadcast,
 * and no key is ever in them. :data keeps it in a file. Never throws.
 */
interface SentTransfers {

    /** Every transaction recorded, re-emitted on each change, or that the record cannot be read. */
    fun observe(): Flow<SentTransferList>

    /** Records [transfer], which a peer took; false when it could not be saved. */
    suspend fun record(transfer: SentTransfer): Boolean

    /**
     * Forgets [account]'s transactions whose nonce is below [minedCount], its verified count of
     * mined transactions (each of those nonces is used on chain, by that transaction or another),
     * and those a peer took before [sentBefore] (unix seconds), which no block took in time. False
     * when the record could not be saved.
     */
    suspend fun prune(account: Address, minedCount: Long, sentBefore: Long): Boolean
}

/** What [SentTransfers.observe] holds. */
sealed interface SentTransferList {

    /** The [transfers] recorded, oldest first. */
    data class Loaded(val transfers: List<SentTransfer>) : SentTransferList

    /** The record cannot be read right now (a newer format, a file that cannot be read). */
    data object Unreadable : SentTransferList
}

/**
 * A transaction this wallet signed from [account] and a peer took: its [nonce] and [signed]
 * bytes, what it does ([action]), the most it can take of the account's native balance
 * ([maxCost]: its value and the most its fee can be), and when a peer took it ([sentAt], unix
 * seconds): the moment its lifetime in the network's transaction pools runs from. The nonce and
 * the cost are what hold a later transaction back while this one is not mined, whatever its
 * action.
 */
data class SentTransfer(
    val account: Address,
    val nonce: Long,
    val signed: SignedTransfer,
    val action: SentAction,
    val maxCost: UInt256,
    val sentAt: Long,
) {
    /** The transaction hash its receipt is watched by. */
    val hash: String
        get() = signed.hash
}

/**
 * What a [SentTransfer] does: one variant per kind of transaction this wallet sends, each with
 * what its status shows of it (docs/wallet-ux.md, screen 9).
 */
sealed interface SentAction {

    /**
     * [amount] of [asset] to [to]: the transaction's value for the native currency, the token
     * contract's `transfer` for a token, whose amount the account's later transfers of that token
     * cannot move while this one is not mined.
     */
    data class Transfer(val to: Address, val asset: TransferAsset, val amount: UInt256) : SentAction

    /**
     * The account's on-chain approval (`approveHash`) of [request]'s Safe transaction, sent to its
     * Safe: it moves nothing of the account's but the fee.
     */
    data class SafeApproval(val request: SigningRequest) : SentAction

    /**
     * The execution (`execTransaction`) of [request]'s Safe transaction, sent to its Safe: what it
     * moves is the Safe's, and the account pays the fee. [request] is the transaction as it was
     * queued; the confirmations it was executed with (as many as the threshold, approvals among
     * them) are in the signed bytes.
     */
    data class SafeExecution(val request: SigningRequest) : SentAction

    /**
     * The deployment of a new Safe at [safe] through its release's proxy factory
     * (`createProxyWithNonce`): of Safe [version], with [owners] in their order and [threshold],
     * to be listed in the wallet as [label] (blank: named as a watched account is) once the light
     * client proves it. It moves nothing of the account's but the fee.
     */
    data class SafeDeployment(
        val safe: Address,
        val owners: List<Address>,
        val threshold: Int,
        val version: String,
        val label: String,
    ) : SentAction
}
