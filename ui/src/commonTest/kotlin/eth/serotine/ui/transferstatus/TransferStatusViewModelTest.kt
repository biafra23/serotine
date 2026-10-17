package eth.serotine.ui.transferstatus

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TransactionProgress
import eth.serotine.domain.usecase.ListDeployedSafe
import eth.serotine.domain.usecase.ObserveSentTransfer
import eth.serotine.domain.usecase.WatchAddress
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeReceipts
import eth.serotine.ui.MemorySentTransfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferStatusViewModelTest {

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val record = SentTransfer(
        account = Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        nonce = 7,
        signed = SignedTransfer(byteArrayOf(2, 1, 9)),
        action = SentAction.Transfer(
            to = Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
            asset = TransferAsset.Erc20Token(usdc),
            amount = UInt256.of(40_500_000),
        ),
        maxCost = UInt256.of(1_000_000),
        sentAt = 1_800_000_000,
    )
    private val sent = MemorySentTransfers()
    private val receipts = FakeReceipts()
    private val accounts = FakeAccounts()
    private val kind = MutableStateFlow<KindReading>(KindReading.Verified(AddressKind.Eoa, 9_412_118))
    private val kinds = object : AddressKinds {
        override fun observeKind(address: Address): Flow<KindReading> = kind
    }

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(hash: String = record.hash): TransferStatusViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = TransferStatusViewModel(
            hash,
            ObserveSentTransfer(sent, receipts),
            ListDeployedSafe(kinds, WatchAddress(accounts, kinds)),
            Network.SEPOLIA,
        ) { "at $it" }
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
    fun anApprovalOrAnExecutionOfASafeTransactionSaysWhatItIsOfWhichSafeAndNonce() = runTest {
        val safe = Address.of("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb")
        val request = SigningRequest.of(
            Network.SEPOLIA,
            safe,
            SafeTx.call(Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"), UInt256.of(1), ByteArray(0), 12),
        )
        val approval =
            record.copy(signed = SignedTransfer(byteArrayOf(2, 1, 8)), action = SentAction.SafeApproval(request))
        val execution =
            record.copy(signed = SignedTransfer(byteArrayOf(2, 1, 7)), action = SentAction.SafeExecution(request))
        sent.transfers.value = listOf(approval, execution)

        val approved = viewModel(approval.hash)
        val executed = viewModel(execution.hash)
        receipts.of(execution.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        assertNull(approved.shown.sent)
        assertEquals(
            SafeTxSummary(
                "Approval of a Safe transaction",
                "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
                "Safe nonce 12",
                "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
            ),
            approved.shown.safeTx,
        )
        assertEquals("Execution of a Safe transaction", executed.shown.safeTx?.title)
        assertEquals(
            "It failed: the transaction reverted in its block, so it did nothing. Its fee is paid all the same.",
            executed.shown.note,
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
        assertNull(shown.safeTx)
        assertEquals(StatusStep("Signed", StepState.DONE, null), shown.steps[0])
        assertEquals(listOf(StepState.UNKNOWN, StepState.UNKNOWN), shown.steps.drop(2).map { it.state })
        assertEquals("Where it stands cannot be verified right now: the light client refuses to sync.", shown.note)
        assertEquals(hash, shown.hash)
    }

    private val safe = Address.of("0x2ca0226086a7c24808217f7f8707827608b17dc5")
    private val deployment = record.copy(
        signed = SignedTransfer(byteArrayOf(2, 1, 10)),
        action = SentAction.SafeDeployment(
            safe = safe,
            owners = listOf(record.account, Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359")),
            threshold = 1,
            version = "1.4.1",
            label = "Family vault",
        ),
    )

    @Test
    fun aSafesDeploymentIsListedOnceItsBlockIsVerified() = runTest {
        sent.transfers.value = listOf(deployment)
        val viewModel = viewModel(deployment.hash)
        runCurrent()

        val pending = assertNotNull(viewModel.shown.deployment)
        assertEquals("Deployment of a Safe", pending.title)
        assertEquals("0x2Ca0226086a7C24808217F7F8707827608B17Dc5", pending.safe)
        assertEquals("1/2", pending.signers)
        assertEquals("1 of 2 owners · Safe 1.4.1", pending.shape)
        assertEquals("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", pending.from)
        assertTrue(pending.listing.startsWith("Added to your wallet once a block has it"))
        assertNull(viewModel.shown.sent)

        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        runCurrent()
        assertEquals(
            "Checking the new Safe with the light client before it is listed…",
            viewModel.shown.deployment?.listing,
            "codeless still, at the head the light client verified",
        )
        assertEquals(emptyList(), accounts.accounts.value)

        val owners = (deployment.action as SentAction.SafeDeployment).owners
        kind.value = KindReading.Verified(
            AddressKind.Safe(SafeState("1.4.1", false, owners, 1, 0, emptyList(), null)),
            9_412_121,
        )
        runCurrent()

        assertEquals("Added to your wallet as Family vault.", viewModel.shown.deployment?.listing)
        assertEquals(
            listOf(Account(safe, AccountKind.SAFE, "Family vault", AccountCustody.Watched)),
            accounts.accounts.value,
        )
    }

    @Test
    fun aDeploymentThatRevertedListsNothing() = runTest {
        sent.transfers.value = listOf(deployment)
        val viewModel = viewModel(deployment.hash)
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = false)
        runCurrent()

        val shown = viewModel.shown
        assertEquals(
            "It failed: the deployment reverted in its block, so no Safe was created. Its fee is paid all the same.",
            shown.note,
        )
        assertTrue(shown.deployment!!.listingFailed)
        assertEquals(emptyList(), accounts.accounts.value)
    }

    @Test
    fun somethingElseAtTheAddressIsNotListed() = runTest {
        sent.transfers.value = listOf(deployment)
        val viewModel = viewModel(deployment.hash)
        receipts.of(deployment.hash).value = TransactionProgress.Included(9_412_120, succeeded = true)
        kind.value = KindReading.Verified(AddressKind.Contract, 9_412_121)
        runCurrent()

        assertTrue(viewModel.shown.deployment!!.listingFailed)
        assertEquals(emptyList(), accounts.accounts.value)
    }
}
