package eth.serotine.ui.transferstatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeListing
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.SentTransferStatus
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The transfer status screen's ViewModel (docs/wallet-ux.md, screen 9): the transfer [hash] on
 * [network], what this device recorded of it and where its verified receipt says it stands
 * ([ObserveSentTransfer]), as [TransferStatusUiState]. [formatTime] dates the broadcast a peer
 * took in the user's own time zone. No intents: the screen's only action, Done, is navigation.
 *
 * A Safe's deployment is listed in the wallet by `FollowSafeDeployments`, which the app runs
 * whatever screen is open (docs/wallet-ux.md, screen 15); this screen only shows where that
 * stands, from the deployment's record and the account list ([ObserveAccounts]).
 */
class TransferStatusViewModel(
    private val hash: String,
    observeSentTransfer: ObserveSentTransfer,
    observeAccounts: ObserveAccounts,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    val uiState: StateFlow<TransferStatusUiState> =
        combine(observeSentTransfer(hash), observeAccounts(), ::render).stateIn(
            viewModelScope,
            // Back after the timeout, no old step stands as current.
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            TransferStatusUiState.Loading(network.displayName),
        )

    private fun render(status: SentTransferStatus, list: AccountList): TransferStatusUiState.Shown {
        val progress = status.progress
        // Broadcast: this screen opens for a transfer a peer took, and its record says when.
        val signed = StatusStep("Signed", StepState.DONE, status.transfer?.let { "On this device" })
        val takenAt = status.transfer?.let { ", ${formatTime(it.sentAt)}" }.orEmpty()
        val broadcast = StatusStep("Broadcast", StepState.DONE, "Handed to the network's peers$takenAt")
        val included = when (progress) {
            TransactionProgress.Pending ->
                StatusStep("Included", StepState.WAITING, "Waiting for a block to include it")
            is TransactionProgress.Included -> includedStep(progress.blockNumber, progress.succeeded)
            is TransactionProgress.Finalized -> includedStep(progress.blockNumber, progress.succeeded)
            is TransactionProgress.Unavailable ->
                StatusStep("Included", StepState.UNKNOWN, "Cannot be verified right now")
        }
        val finalized = when (progress) {
            TransactionProgress.Pending, is TransactionProgress.Included ->
                StatusStep("Finalized", StepState.WAITING, "Once the beacon chain finalizes its block")
            is TransactionProgress.Finalized ->
                StatusStep("Finalized", StepState.DONE, "Block ${grouped(progress.blockNumber)} is finalized")
            is TransactionProgress.Unavailable ->
                StatusStep("Finalized", StepState.UNKNOWN, "Cannot be verified right now")
        }
        val failed = (progress as? TransactionProgress.Included)?.succeeded == false ||
            (progress as? TransactionProgress.Finalized)?.succeeded == false
        val transfer = status.transfer
        return TransferStatusUiState.Shown(
            network = network.displayName,
            hash = hash,
            sent = transfer?.let(::summary),
            steps = listOf(signed, broadcast, included, finalized),
            note = when {
                progress is TransactionProgress.Unavailable -> unavailable(progress.reason)
                failed && transfer?.action is SentAction.Transfer ->
                    "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same."
                failed && transfer?.action is SentAction.SafeDeployment ->
                    "It failed: this transaction reverted in its block and created no Safe itself. Its fee is " +
                        "paid all the same."
                failed -> "It failed: the transaction reverted in its block, so it did nothing. Its fee is paid all " +
                    "the same."
                else -> null
            },
            finished = progress is TransactionProgress.Finalized,
            safeTx = transfer?.let(::safeTxSummary),
            deployment = transfer?.let { deploymentSummary(it, progress, failed, list) },
        )
    }

    /** What [transfer] created, when it is a Safe's deployment, and where the Safe's listing stands. */
    private fun deploymentSummary(
        transfer: SentTransfer,
        progress: TransactionProgress,
        failed: Boolean,
        list: AccountList,
    ): DeploymentSummary? {
        val deployment = transfer.action as? SentAction.SafeDeployment ?: return null
        val owners = deployment.owners.size
        // After a revert, a Safe at the address is another sender's copy of the same call's doing.
        val copied = if (failed) {
            "A Safe with these owners and threshold is at its address all the same: another sender's copy of " +
                "the same deployment created it. "
        } else {
            ""
        }
        // The name it has in the wallet now, or the one it was to be listed under.
        val label = (list as? AccountList.Loaded)?.accounts?.firstOrNull { it.address == deployment.safe }?.label
            ?: deployment.label
        val mined = progress is TransactionProgress.Included || progress is TransactionProgress.Finalized
        val (text, listingFailed) = when (deployment.listed) {
            SafeListing.LISTED -> "${copied}In your wallet as $label." to false
            SafeListing.NO_SAFE ->
                "This transaction created no Safe, and none is at its address: nothing is added to your wallet." to
                    true
            SafeListing.NOT_THE_SAFE -> "The address does not hold the Safe deployed, as the light client " +
                "verifies it, so it was not added to your wallet." to true
            null -> when {
                failed -> "Checking whether a Safe is at its address all the same…" to false
                mined -> "Checking the new Safe with the light client before it is listed…" to false
                else -> "Added to your wallet once a block has it and the light client verifies the Safe there, " +
                    "whether this screen is open or not." to false
            }
        }
        return DeploymentSummary(
            title = "Deployment of a Safe",
            safe = Eip55.format(deployment.safe),
            signers = "${deployment.threshold}/$owners",
            shape = "${deployment.threshold} of $owners ${if (owners == 1) "owner" else "owners"} · " +
                "Safe ${deployment.version}",
            from = Eip55.format(transfer.account),
            listing = text,
            listingFailed = listingFailed,
        )
    }

    private fun includedStep(block: Long, succeeded: Boolean) = if (succeeded) {
        StatusStep("Included", StepState.DONE, "In block ${grouped(block)}, its receipt verified")
    } else {
        StatusStep("Included", StepState.FAILED, "In block ${grouped(block)}, its receipt verified: it reverted")
    }

    /** What [transfer] sent, when it is a transfer. */
    private fun summary(transfer: SentTransfer): SentSummary? {
        val sent = transfer.action as? SentAction.Transfer ?: return null
        val (symbol, decimals, icon) = when (val asset = sent.asset) {
            TransferAsset.Native -> Triple(network.nativeSymbol, NATIVE_DECIMALS, network.nativeIcon)
            is TransferAsset.Erc20Token ->
                Triple(displaySymbol(asset.token.symbol), asset.token.decimals, asset.token.icon)
        }
        return SentSummary(
            amount = "${sent.amount.formatUnits(decimals)} $symbol",
            symbol = symbol,
            icon = icon,
            to = Eip55.format(sent.to),
            from = Eip55.format(transfer.account),
        )
    }

    /** What [transfer] did, when it is an approval or an execution of a Safe transaction. */
    private fun safeTxSummary(transfer: SentTransfer): SafeTxSummary? {
        val (title, request) = when (val action = transfer.action) {
            is SentAction.SafeApproval -> "Approval of a Safe transaction" to action.request
            is SentAction.SafeExecution -> "Execution of a Safe transaction" to action.request
            is SentAction.Transfer, is SentAction.SafeDeployment -> return null
        }
        return SafeTxSummary(
            title = title,
            safe = Eip55.format(request.safe),
            nonce = "Safe nonce ${request.nonce}",
            from = Eip55.format(transfer.account),
        )
    }

    private fun unavailable(reason: ReadUnavailable): String = "Where it stands cannot be verified right now: " +
        when (reason) {
            ReadUnavailable.NO_ENGINE -> "there is no light client on this device."
            ReadUnavailable.STALE_ANCHOR -> "the light client refuses to sync."
            ReadUnavailable.UNPROVEN -> "its receipt could not be verified this time. Serotine keeps checking."
            ReadUnavailable.REFUSED -> "the light client on this device cannot read receipts."
        }

    private companion object {
        const val NATIVE_DECIMALS = 18
    }
}
