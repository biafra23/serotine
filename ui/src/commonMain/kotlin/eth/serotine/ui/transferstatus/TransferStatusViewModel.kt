package eth.serotine.ui.transferstatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.DeployedSafeListing
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.SentTransferStatus
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The transfer status screen's ViewModel (docs/wallet-ux.md, screen 9): the transfer [hash] on
 * [network], what this device recorded of it and where its verified receipt says it stands
 * ([ObserveSentTransfer]), as [TransferStatusUiState]. [formatTime] dates the broadcast a peer
 * took in the user's own time zone. No intents: the screen's only action, Done, is navigation.
 *
 * A Safe's deployment is listed in the wallet from here ([ListDeployedSafe]) once its receipt
 * says a block took it and it succeeded: the Safe is detected like a watched one first
 * (docs/wallet-ux.md, screen 15). That runs while the screen is open; one left before has its
 * Safe added with Watch address, as the screen says.
 */
class TransferStatusViewModel(
    private val hash: String,
    observeSentTransfer: ObserveSentTransfer,
    private val listDeployedSafe: ListDeployedSafe,
    private val network: Network,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {

    /** Where the listing of a deployed Safe stands. */
    private sealed interface Listing {
        data object Idle : Listing

        data object Running : Listing

        data class Done(val listing: DeployedSafeListing) : Listing

        /** The use case failed, against its promise: the user adds the Safe with Watch address. */
        data object Broken : Listing
    }

    private val listing = MutableStateFlow<Listing>(Listing.Idle)
    private var listingJob: Job? = null

    val uiState: StateFlow<TransferStatusUiState> = combine(
        observeSentTransfer(hash).onEach(::listOnceMined),
        listing,
        ::render,
    ).stateIn(
        viewModelScope,
        // Back after the timeout, no old step stands as current.
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        TransferStatusUiState.Loading(network.displayName),
    )

    /**
     * Lists the Safe [status] deployed once its receipt is verified, once: when it succeeded, as
     * soon as the light client proves the Safe (the use case waits for that, and tries again itself
     * while it cannot verify it); when it reverted, if the address, read once, holds the Safe
     * planned all the same — someone's copy of the same call mined first creates the very same Safe.
     */
    private fun listOnceMined(status: SentTransferStatus) {
        val deployment = status.transfer?.action as? SentAction.SafeDeployment ?: return
        val succeeded = succeeded(status.progress) ?: return
        if (listing.value != Listing.Idle || listingJob?.isActive == true) return
        listing.value = Listing.Running
        listingJob = viewModelScope.launch {
            listing.value = try {
                Listing.Done(
                    if (succeeded) listDeployedSafe(deployment) else listDeployedSafe.afterRevert(deployment),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The use case promises not to throw; the backstop ends it, since a finalized receipt
                // is never read again.
                Listing.Broken
            }
        }
    }

    /** Whether the verified receipt says it succeeded (true) or reverted (false); null before it has one. */
    private fun succeeded(progress: TransactionProgress): Boolean? = when (progress) {
        is TransactionProgress.Included -> progress.succeeded
        is TransactionProgress.Finalized -> progress.succeeded
        else -> null
    }

    private fun render(status: SentTransferStatus, listing: Listing): TransferStatusUiState.Shown {
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
            deployment = transfer?.let { deploymentSummary(it, progress, failed, listing) },
        )
    }

    /** What [transfer] created, when it is a Safe's deployment, and where the Safe's listing stands. */
    private fun deploymentSummary(
        transfer: SentTransfer,
        progress: TransactionProgress,
        failed: Boolean,
        listing: Listing,
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
        val (text, listingFailed) = when {
            listing is Listing.Done -> when (val done = listing.listing) {
                is DeployedSafeListing.Listed -> "${copied}Added to your wallet as ${done.account.label}." to false
                is DeployedSafeListing.AlreadyInWallet -> "${copied}In your wallet as ${done.account.label}." to false
                DeployedSafeListing.NoSafe ->
                    "This transaction created no Safe, and none is at its address: nothing is added to your " +
                        "wallet." to true
                is DeployedSafeListing.NotVerified -> "Whether a Safe is at its address all the same cannot be " +
                    "verified right now: check it later with Watch address." to true
                DeployedSafeListing.NotTheSafe -> "The address does not hold the Safe deployed, as the light " +
                    "client verifies it, so it was not added to your wallet." to true
                DeployedSafeListing.NotSaved -> "Your account list cannot be saved right now, so the Safe was not " +
                    "added: add it with Watch address." to true
            }
            listing == Listing.Broken -> "Serotine could not finish listing the new Safe: add it with Watch " +
                "address." to true
            failed -> "Checking whether a Safe is at its address all the same…" to false
            listing == Listing.Running || succeeded(progress) != null ->
                "Checking the new Safe with the light client before it is listed…" to false
            else -> "Added to your wallet once a block has it and the light client verifies the Safe there, while " +
                "this screen is open; after leaving it, add it with Watch address." to false
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
