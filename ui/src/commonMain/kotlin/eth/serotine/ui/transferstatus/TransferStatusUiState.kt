package eth.serotine.ui.transferstatus

import eth.serotine.ui.components.NotSentItem

/**
 * Everything the transfer status screen renders (docs/wallet-ux.md, screen 9): what was sent,
 * and the steps Signed → Broadcast → Included → Finalized, each with its block once it has one.
 * Display-ready; every step past Broadcast is what the verified receipt says.
 */
sealed interface TransferStatusUiState {

    /** The network's name ("Sepolia"). */
    val network: String

    /** Nothing read yet. */
    data class Loading(override val network: String) : TransferStatusUiState

    /**
     * The transaction [hash]: what was sent ([sent] for a transfer, [safeTx] for a Safe
     * transaction's approval or execution, [deployment] for a Safe's deployment; all null when this
     * device has no record of it), its [steps] in order, and [note], what to know about where it
     * stands (a failure, or that it cannot be verified right now), or null. [finished] once its
     * block is finalized. [notSent] while it is recorded as maybe sent (no peer answered its
     * broadcast) and no block has it, with Try again, which sends the same signed bytes
     * ([retrying] while it does); or what such a retry came to, once there is nothing to retry.
     */
    data class Shown(
        override val network: String,
        val hash: String,
        val sent: SentSummary?,
        val steps: List<StatusStep>,
        val note: String? = null,
        val finished: Boolean = false,
        val safeTx: SafeTxSummary? = null,
        val deployment: DeploymentSummary? = null,
        val notSent: NotSentItem? = null,
        val retrying: Boolean = false,
    ) : TransferStatusUiState
}

/**
 * What was sent, from this device's record: the [amount] with its symbol, the asset's [symbol] and
 * bundled [icon] (null: a monogram), and the EIP-55 [to] and [from] addresses.
 */
data class SentSummary(
    val amount: String,
    val symbol: String,
    val icon: String?,
    val to: String,
    val from: String,
)

/**
 * What an approval or an execution of a Safe transaction did, from this device's record: [title]
 * ("Approval of a Safe transaction"), the EIP-55 [safe] it is of and its [nonce] ("Safe nonce 4"),
 * and the EIP-55 [from], the account that sent it.
 */
data class SafeTxSummary(val title: String, val safe: String, val nonce: String, val from: String)

/**
 * What a Safe's deployment created, from this device's record: [title] ("Deployment of a Safe"),
 * the EIP-55 [safe] address with its [signers] badge ("2/3"), its [shape] ("2 of 3 owners · Safe
 * 1.4.1"), the EIP-55 [from], the account that paid, and [listing], where the Safe's listing in
 * the wallet stands — [listingFailed] when it was not, and will not be, listed from here.
 */
data class DeploymentSummary(
    val title: String,
    val safe: String,
    val signers: String,
    val shape: String,
    val from: String,
    val listing: String,
    val listingFailed: Boolean = false,
)

/** One step of a transfer: its [label] ("Included"), where it stands ([state]) and its [detail] (its block). */
data class StatusStep(val label: String, val state: StepState, val detail: String?)

/** Where a [StatusStep] stands. */
enum class StepState {
    /** Done: verified, or done by this device (signing, broadcasting). */
    DONE,

    /** Not yet. */
    WAITING,

    /** Done, and the transfer failed in it: included, but reverted. */
    FAILED,

    /** Where it stands cannot be verified right now. */
    UNKNOWN,

    /** Done by this device, but no peer confirmed it: a broadcast maybe sent, until a block has it. */
    UNCONFIRMED,
}
