package eth.serotine.ui.transferstatus

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.MemorySentTransfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferStatusViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val record = SentTransfer(
        account = Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        nonce = 7,
        signed = SignedTransfer(byteArrayOf(2, 1, 9)),
        to = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        asset = TransferAsset.Erc20Token(usdc),
        amount = UInt256.of(40_500_000),
        maxCost = UInt256.of(1_000_000),
        sentAt = 1_800_000_000,
    )
    private val sent = MemorySentTransfers()
    private val receipts = FakeReceipts()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(hash: String = record.hash): TransferStatusViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = TransferStatusViewModel(hash, ObserveSentTransfer(sent, receipts), Network.SEPOLIA) {
            "at $it"
        }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private val TransferStatusViewModel.shown get() = assertIs<TransferStatusUiState.Shown>(uiState.value)

    @Test
    fun whatWasSentAndTheStepsAsTheVerifiedReceiptMovesOn() = runTest {
        sent.transfers.value = listOf(record)
        val viewModel = viewModel()
        runCurrent()

        val pending = viewModel.shown
        assertEquals(
            SentSummary(
                amount = "40.5 USDC",
                symbol = "USDC",
                icon = "usdc",
                to = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                from = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            pending.sent,
        )
        assertEquals(
            listOf(
                StatusStep("Signed", StepState.DONE, "On this device"),
                StatusStep("Broadcast", StepState.DONE, "Handed to the network's peers, at 1800000000"),
                StatusStep("Included", StepState.WAITING, "Waiting for a block to include it"),
                StatusStep("Finalized", StepState.WAITING, "Once the beacon chain finalizes its block"),
            ),
            pending.steps,
        )
        assertNull(pending.note)

        receipts.of(record.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        assertEquals(
            StatusStep("Included", StepState.DONE, "In block 9,412,120, its receipt verified"),
            viewModel.shown.steps[2],
        )
        assertFalse(viewModel.shown.finished)

        receipts.of(record.hash).value = TransactionProgress.Finalized(9_412_120, succeeded = true)
        assertEquals(
            StatusStep("Finalized", StepState.DONE, "Block 9,412,120 is finalized"),
            viewModel.shown.steps[3],
        )
        assertTrue(viewModel.shown.finished)
    }

    @Test
    fun aTransferThatRevertedIsSaidToHaveFailed() = runTest {
        sent.transfers.value = listOf(record)
        val viewModel = viewModel()
        receipts.of(record.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        assertEquals(
            StatusStep("Included", StepState.FAILED, "In block 9,412,120, its receipt verified: it reverted"),
            viewModel.shown.steps[2],
        )
        assertEquals(
            "It failed: the transfer reverted in its block, so nothing was moved. Its fee is paid all the same.",
            viewModel.shown.note,
        )
    }

    @Test
    fun aReceiptThatCannotBeVerifiedReadsAsSuchAndAMissingRecordStillShowsTheSteps() = runTest {
        val hash = "0x" + "ab".repeat(32)
        val viewModel = viewModel(hash)
        receipts.of(hash).value = TransactionProgress.Unavailable(ReadUnavailable.STALE_ANCHOR)
        runCurrent()

        val shown = viewModel.shown
        assertNull(shown.sent, "no record on this device")
        assertEquals(StatusStep("Signed", StepState.DONE, null), shown.steps[0])
        assertEquals(listOf(StepState.UNKNOWN, StepState.UNKNOWN), shown.steps.drop(2).map { it.state })
        assertEquals("Where it stands cannot be verified right now: the light client refuses to sync.", shown.note)
        assertEquals(hash, shown.hash)
    }
}
