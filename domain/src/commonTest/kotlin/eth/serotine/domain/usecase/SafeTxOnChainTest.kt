package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeCalls
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.ApprovalReading
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SafeTxOnChainTest {

    private val world = SafeWorld()
    private val unlock = Unlock.WithBiometrics("Send the Safe transaction")
    private val payment = SafeTx.call(Address.of("0x" + "bb".repeat(20)), UInt256.of(5), ByteArray(0), nonce = 4)

    private val quoteApproval = QuoteSafeApproval(
        world.observeSafe,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        Network.SEPOLIA,
    ) { 1_800_000_000 }
    private val quoteExecution = QuoteSafeExecution(
        world.observeSafe,
        world.recovery,
        world.approvals,
        world.nativeBalances,
        world.inputs,
        world.sent,
        Network.SEPOLIA,
    ) { 1_800_000_000 }

    private val executed = "0".repeat(63) + "1"

    @Test
    fun eachOwnerStandsAsItsSignatureOrItsApprovalOnChainSays() = runTest {
        val request = world.request(payment, world.alice.address, world.stranger)
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 120)

        val reading = assertIs<SafeTxOwnersReading.Read>(
            ReadSafeTxOwners(world.observeSafe, world.recovery, world.approvals, Network.SEPOLIA)(request),
        )

        assertEquals(
            listOf(
                SafeTxOwner(world.alice.address, held = true, OwnerConfirmation.Signed),
                SafeTxOwner(world.bob.address, held = false, OwnerConfirmation.ApprovedOnChain(120)),
                SafeTxOwner(world.carol, held = false, OwnerConfirmation.Missing),
            ),
            reading.owners,
            "a stranger's signature counts for nobody",
        )
        assertEquals(2, reading.confirmations)
        assertEquals(2, reading.threshold)
    }

    @Test
    fun anOwnerApprovesTheHashOnChainFromItsAccount() = runTest {
        val request = world.request(payment)

        val quote = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(request, world.alice.address, FeeLevel.NORMAL),
        ).quote

        val tx = quote.call.transaction
        assertEquals(world.safe, tx.to)
        assertEquals(UInt256.ZERO, tx.value)
        assertContentEquals(SafeCalls.approveHash(request.hash), tx.data)
        assertEquals(7, tx.nonce, "the account's own nonce")
        assertEquals(world.alice.address to world.safe, world.inputs.simulated.single().let { it.first to it.second })

        val sending = ApproveSafeTxOnChain(world.signer, world.broadcaster, world.sent) { 1_800_000_001 }
        assertIs<TransferSending.Sent>(sending(quote, unlock))
        assertEquals(world.alice.address to tx.signingDigest(), world.signer.asked.single())
        val record = world.sent.transfers.value.single()
        assertEquals(SentAction.SafeApproval(request), record.action)
        assertEquals(7, record.nonce)
        assertEquals(quote.call.maxFee, record.maxCost)
    }

    @Test
    fun anApprovalIsOnlyAnOwnersOnceForATransactionThatCanStillRun() = runTest {
        val request = world.request(payment)
        suspend fun refusal(request: SigningRequest, owner: Address) =
            assertIs<SafeApprovalQuoting.Refused>(quoteApproval(request, owner, FeeLevel.NORMAL)).refusal

        assertEquals(SafeTxRefusal.NotAnOwner(world.stranger), refusal(request, world.stranger))
        val passed = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 3))
        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(passed, world.alice.address))
        world.approvals.approved[world.alice.address] = ApprovalReading.Read(approved = true, blockNumber = 120)
        assertEquals(SafeTxRefusal.AlreadyApproved, refusal(request, world.alice.address))
        world.approvals.approved[world.alice.address] = ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), refusal(request, world.alice.address))
        assertEquals(emptyList(), world.inputs.simulated, "nothing quoted")
    }

    @Test
    fun anExecutingOwnerConfirmsItAndTheSignaturesArePackedByOwner() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val request = world.request(payment, world.carol)

        val quote = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.alice.address, FeeLevel.FAST),
        ).quote

        val carols = request.signatures.single()
        assertEquals(listOf(SafeSignature.ApprovedHash(world.alice.address), carols), quote.signatures)
        assertEquals(emptyList(), world.approvals.asked, "two of two without asking the chain for approvals")
        val tx = quote.call.transaction
        assertEquals(world.safe, tx.to)
        assertContentEquals(SafeCalls.execTransaction(payment, quote.signatures), tx.data)
        assertEquals(world.alice.address, world.inputs.simulated.single().first, "run from the executor")

        val execute = ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_002 }
        val sent = assertIs<TransferSending.Sent>(execute(quote, unlock))
        val record = world.sent.transfers.value.single()
        assertEquals(SentAction.SafeExecution(request), record.action)
        assertEquals(Eip1559Transaction.hashOf(world.broadcaster.sent.single()), record.hash)
        assertEquals(record.hash, sent.hash)
    }

    @Test
    fun anyAccountHeldHereExecutesWithApprovalsOnChainMakingUpTheThreshold() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        world.approvals.approved[world.bob.address] = ApprovalReading.Read(approved = true, blockNumber = 120)
        val request = world.request(payment, world.carol)

        val quote = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.stranger, FeeLevel.NORMAL),
        ).quote

        assertEquals(
            listOf(request.signatures.single(), SafeSignature.ApprovedHash(world.bob.address)),
            quote.signatures,
        )
        assertEquals(listOf(world.alice.address, world.bob.address), world.approvals.asked)
    }

    @Test
    fun anExecutionShortOfTheThresholdOrOutOfTurnIsRefused() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        suspend fun refusal(request: SigningRequest, executor: Address = world.stranger) =
            assertIs<SafeExecutionQuoting.Refused>(quoteExecution(request, executor, FeeLevel.NORMAL)).refusal

        assertEquals(SafeTxRefusal.ThresholdNotMet(1, 2), refusal(world.request(payment, world.carol)))
        // A former owner's signature counts for nothing.
        world.state = world.state.copy(owners = listOf(world.alice.address, world.bob.address), threshold = 1)
        assertEquals(SafeTxRefusal.ThresholdNotMet(0, 1), refusal(world.request(payment, world.carol)))
        world.state = world.state.copy(
            owners = listOf(world.alice.address, world.bob.address, world.carol),
            threshold = 2,
        )

        world.approvals.approved[world.bob.address] = ApprovalReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), refusal(world.request(payment, world.carol)))

        val later = SafeTx.call(payment.to, payment.value, ByteArray(0), nonce = 5)
        assertEquals(SafeTxRefusal.NotNext(4), refusal(world.request(later, world.alice.address, world.carol)))
        val passed = SafeTx.call(payment.to, payment.value, ByteArray(0), nonce = 3)
        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(world.request(passed, world.alice.address, world.carol)))
        val delegate = SafeTx(
            payment.to, UInt256.ZERO, ByteArray(0), SafeOperation.DELEGATECALL,
            UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, nonce = 4,
        )
        assertEquals(SafeTxRefusal.DelegateCall, refusal(world.request(delegate, world.alice.address, world.carol)))
        assertEquals(emptyList(), world.inputs.simulated, "nothing quoted")
    }

    @Test
    fun anExecutionThatWouldFailOrRevertStopsAtTheQuote() = runTest {
        val request = world.request(payment, world.alice.address, world.carol)

        // false: the Safe's call failed under a gas price or safeTxGas, and its nonce would be used up.
        world.inputs.simulation = Simulation.Succeeded("0".repeat(64), 101)
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.NotQuoted(CallRefusal.Reverts(101))),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
        world.inputs.estimate = GasEstimate.Reverted("")
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.NotQuoted(CallRefusal.Reverts(null))),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )
    }

    @Test
    fun anExecutionOrApprovalHoldsItsNonceAndItsCostLikeATransfer() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val first = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(world.request(payment, world.carol), world.alice.address, FeeLevel.NORMAL),
        ).quote
        ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(first, unlock)

        world.inputs.simulation = Simulation.Succeeded("", 100)
        val next = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 5))
        val approval = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(next, world.alice.address, FeeLevel.NORMAL),
        ).quote

        assertEquals(8, approval.call.transaction.nonce, "after the execution not mined yet")
        assertEquals(first.call.maxCost, approval.call.pendingCost)
    }

    @Test
    fun anExecutionOrApprovalSentAndNotMinedIsNotSentAgain() = runTest {
        world.inputs.simulation = Simulation.Succeeded(executed, 100)
        val request = world.request(payment, world.carol)
        val first = assertIs<SafeExecutionQuoting.Quoted>(
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        ).quote
        ExecuteSafeTx(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(first, unlock)

        // At the head the Safe's nonce is still 4: the run passes, and the second would revert once mined.
        assertEquals(
            SafeExecutionQuoting.Refused(SafeTxRefusal.AlreadySent),
            quoteExecution(request, world.alice.address, FeeLevel.NORMAL),
        )

        world.inputs.simulation = Simulation.Succeeded("", 100)
        val later = world.request(SafeTx.call(world.safe, UInt256.ZERO, ByteArray(0), nonce = 5))
        val approval = assertIs<SafeApprovalQuoting.Quoted>(
            quoteApproval(later, world.alice.address, FeeLevel.NORMAL),
        ).quote
        ApproveSafeTxOnChain(world.signer, world.broadcaster, world.sent) { 1_800_000_000 }(approval, unlock)
        assertEquals(
            SafeApprovalQuoting.Refused(SafeTxRefusal.AlreadySent),
            quoteApproval(later, world.alice.address, FeeLevel.NORMAL),
        )
    }
}
