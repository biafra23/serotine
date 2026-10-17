package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSignature
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.SigningRequestDecoding
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeTxSigningTest {

    private val world = SafeWorld()
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val unlock = Unlock.WithBiometrics("Sign the Safe transaction")

    private fun usdcTransfer(nonce: Long, amount: Long = 1_500_000) =
        SafeTx.call(usdc.address, UInt256.ZERO, Erc20.transferData(world.recipient, UInt256.of(amount)), nonce)

    private val import = ImportSigningRequest(world.codec, world.observeSafe, world.recovery, world.queue)
    private val sign = SignSafeTx(world.signer, world.observeSafe, world.recovery, world.queue, Network.SEPOLIA)
    private val propose = ProposeSafeTx(
        world.observeSafe,
        world.nativeBalances,
        world.tokenBalances,
        world.inputs,
        world.queue,
        Network.SEPOLIA,
    )

    @Test
    fun aRequestCheckedAgainstTheChainIsDecodedHereAndQueued() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        val imported = assertIs<SigningRequestImport.Imported>(import(world.codec.encode(request)))

        assertEquals(
            SafeTxAction.TokenTransfer(usdc.address, world.recipient, UInt256.of(1_500_000)),
            imported.tx.action,
        )
        assertEquals(0, imported.queuedBehind, "the next to run")
        assertEquals(listOf(world.alice.address), imported.safe.ownersHeld)
        assertTrue(imported.saved)
        assertEquals(listOf(request), world.queue.requests.value)
    }

    @Test
    fun aRequestAheadOfTheSafesNonceIsQueuedBehindTheOthers() = runTest {
        val text = world.codec.encode(world.request(usdcTransfer(6)))

        val imported = assertIs<SigningRequestImport.Imported>(import(text))

        assertEquals(2, imported.queuedBehind)
    }

    @Test
    fun theSignaturesItCarriesJoinThoseQueuedHere() = runTest {
        world.queue.save(world.request(usdcTransfer(4), world.alice.address))

        val imported = assertIs<SigningRequestImport.Imported>(
            import(world.codec.encode(world.request(usdcTransfer(4), world.carol))),
        )

        assertEquals(listOf(world.alice.address, world.carol), imported.tx.request.signers)
        assertEquals(listOf(imported.tx.request), world.queue.requests.value)
    }

    @Test
    fun whatTheChainContradictsIsRefusedAndNothingIsKept() = runTest {
        suspend fun refusal(request: SigningRequest) =
            assertIs<SigningRequestImport.Refused>(import(world.codec.encode(request))).refusal

        assertEquals(SafeTxRefusal.NonceUsed(4), refusal(world.request(usdcTransfer(3))))
        assertEquals(SafeTxRefusal.NotAnOwner(world.stranger), refusal(world.request(usdcTransfer(4), world.stranger)))
        // Carol's signature of another transaction, claimed for this one.
        val other = world.request(usdcTransfer(5), world.carol).signatures.single()
        val forged = SigningRequest.of(Network.SEPOLIA, world.safe, usdcTransfer(4), listOf(other))
        assertEquals(SafeTxRefusal.BadSignature(world.carol), refusal(forged))
        // A signature that recovers to Alice, said to be Carol's.
        val alices = world.request(usdcTransfer(4), world.alice.address).signatures.single().signature
        val claimed = SigningRequest.of(
            Network.SEPOLIA,
            world.safe,
            usdcTransfer(4),
            listOf(SafeSignature.Ecdsa(world.carol, alices)),
        )
        assertEquals(SafeTxRefusal.BadSignature(world.carol), refusal(claimed))

        world.kind.value = KindReading.Verified(AddressKind.Contract, 100)
        assertEquals(SafeTxRefusal.NotASafe, refusal(world.request(usdcTransfer(4))))
        world.kind.value = KindReading.Syncing
        assertEquals(SafeTxRefusal.Syncing, refusal(world.request(usdcTransfer(4))))
        world.kind.value = KindReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(SafeTxRefusal.Unavailable(ReadUnavailable.UNPROVEN), refusal(world.request(usdcTransfer(4))))
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aTextThatIsNoRequestForThisNetworkIsRefusedAsTheCodecSays() = runTest {
        world.codec.texts["v2"] = SigningRequestDecoding.UnknownVersion(2)
        world.codec.texts["mainnet"] = SigningRequestDecoding.WrongChain(1)

        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.Malformed), import("hello"))
        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.UnknownVersion(2)), import("v2"))
        assertEquals(SigningRequestImport.Refused(SafeTxRefusal.WrongChain(1)), import("mainnet"))
    }

    @Test
    fun aDelegateCallIsShownForWhatItIsAndNeverSigned() = runTest {
        val tx = SafeTx(
            usdc.address, UInt256.ZERO, Erc20.transferData(world.recipient, UInt256.ONE), SafeOperation.DELEGATECALL,
            UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, nonce = 4,
        )
        val request = world.request(tx)

        val imported = assertIs<SigningRequestImport.Imported>(import(world.codec.encode(request)))
        assertTrue(imported.tx.delegateCall)
        assertEquals(SafeTxAction.DelegateCall(usdc.address, "a9059cbb", 68), imported.tx.action)

        assertEquals(SafeTxSigning.Refused(SafeTxRefusal.DelegateCall), sign(request, world.alice.address, unlock))
        assertEquals(emptyList(), world.signer.asked, "nothing reached the key store")
    }

    @Test
    fun anOwnerHeldHereSignsTheHashComputedHere() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        val signed = assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))

        assertEquals(listOf(world.alice.address to request.hash), world.signer.asked)
        assertEquals(listOf(world.alice.address, world.carol), signed.request.signers)
        assertTrue(signed.saved)
        assertEquals(listOf(signed.request), world.queue.requests.value)
    }

    @Test
    fun onlyACurrentOwnerSignsATransactionTheSafeHasNotPassed() = runTest {
        val request = world.request(usdcTransfer(4), world.carol)

        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.NotAnOwner(world.stranger)),
            sign(request, world.stranger, unlock),
        )
        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.AlreadySigned(world.carol)),
            sign(request, world.carol, unlock),
        )
        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.NonceUsed(4)),
            sign(world.request(usdcTransfer(3)), world.alice.address, unlock),
        )
        assertEquals(emptyList(), world.signer.asked)

        world.signer.answer = Signing.WrongPassword
        assertEquals(SafeTxSigning.NotSigned(Signing.WrongPassword), sign(request, world.alice.address, unlock))
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun onlyCheckedSignaturesAreQueuedWithANewOne() = runTest {
        // Carried in from no check at all: a stranger's, and one said to be Carol's that is not.
        val unchecked = world.request(usdcTransfer(4), world.stranger)
        val notCarols = SafeSignature.Ecdsa(world.carol, unchecked.signatures.single().signature)
        val request = unchecked.withSignatures(listOf(notCarols))

        val signed = assertIs<SafeTxSigning.Signed>(sign(request, world.alice.address, unlock))

        assertEquals(listOf(world.alice.address), signed.request.signers)
        assertEquals(listOf(signed.request), world.queue.requests.value)
    }

    @Test
    fun aForgedSignatureInTheOwnersNameNeitherStandsForItNorKeepsItFromSigning() = runTest {
        val strangers = world.request(usdcTransfer(4), world.stranger).signatures.single().signature
        val forged = SigningRequest.of(
            Network.SEPOLIA,
            world.safe,
            usdcTransfer(4),
            listOf(SafeSignature.Ecdsa(world.alice.address, strangers)),
        )

        val signed = assertIs<SafeTxSigning.Signed>(sign(forged, world.alice.address, unlock))

        assertEquals(world.request(usdcTransfer(4), world.alice.address), signed.request)
    }

    @Test
    fun aSignatureOfAnOwnerRemovedSinceIsNeitherSharedNorShown() = runTest {
        world.queue.save(world.request(usdcTransfer(4), world.carol))
        world.state = world.state.copy(owners = listOf(world.alice.address, world.bob.address))

        val signed = assertIs<SafeTxSigning.Signed>(sign(world.request(usdcTransfer(4)), world.alice.address, unlock))
        val queued = assertIs<SafeTxQueueReading.Loaded>(
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA)(world.safe).first(),
        )

        // Shared with Carol's, another device would refuse the whole request.
        assertEquals(listOf(world.alice.address), signed.request.signers)
        assertEquals(listOf(world.alice.address), queued.transactions.single().request.signers)
    }

    @Test
    fun aRequestQueuedFarAheadDoesNotPushAProposalBehindIt() = runTest {
        // Imported with no signature at an absurd nonce: it can only run once the gap is filled.
        world.queue.save(world.request(usdcTransfer(1_000, amount = 4_000_000)))
        world.queue.save(world.request(usdcTransfer(4)))

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Erc20Token(usdc), UInt256.of(2_000_000)),
        ).proposal

        assertEquals(5, proposal.request.nonce)
        assertEquals(1, proposal.queuedAhead)
        assertEquals(UInt256.of(1_500_000), proposal.queuedAmount, "the one at 4 alone")
    }

    @Test
    fun aSignatureThatRecoversToAnotherAddressIsNeverKept() = runTest {
        world.signer.signAs = world.bob.address

        assertEquals(
            SafeTxSigning.Refused(SafeTxRefusal.BadSignature(world.alice.address)),
            sign(world.request(usdcTransfer(4)), world.alice.address, unlock),
        )
        assertEquals(emptyList(), world.queue.requests.value)
    }

    @Test
    fun aTransferIsProposedAtTheSafesNonceAndRunFromTheSafe() = runTest {
        val amount = UInt256.of(100_000_000_000_000_000) // 0.1 ETH of the Safe's 1

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, amount),
        ).proposal

        assertEquals(SafeTx.call(world.recipient, amount, ByteArray(0), nonce = 4), proposal.request.tx)
        assertEquals(emptyList(), proposal.request.signatures)
        assertEquals(SafeTxAction.NativeTransfer(world.recipient, amount), proposal.tx.action)
        assertEquals(UInt256.of(900_000_000_000_000_000), proposal.balanceAfter)
        assertEquals(SafeCallSimulation.Ran(100), proposal.simulation)
        assertEquals(world.safe to world.recipient, world.inputs.simulated.single().let { it.first to it.second })
        assertEquals(emptyList(), world.queue.requests.value, "nothing is queued until it is signed")
    }

    @Test
    fun aTokenTransferIsProposedAfterTheQueueAndWhatItMovesIsNotThisOnesToMove() = runTest {
        world.queue.save(world.request(usdcTransfer(4, amount = 3_000_000), world.carol))
        world.queue.save(world.request(usdcTransfer(2))) // passed: pruned
        val usdcAsset = TransferAsset.Erc20Token(usdc)

        assertEquals(
            SafeTxProposing.InsufficientToken,
            propose(world.safe, world.recipient, usdcAsset, UInt256.of(2_500_000)),
            "5 USDC held, 3 queued before it: 2.5 more would revert once those run",
        )
        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, usdcAsset, UInt256.of(2_000_000)),
        ).proposal

        assertEquals(5, proposal.request.nonce, "after the one queued at 4")
        assertEquals(1, proposal.queuedAhead)
        assertEquals(UInt256.of(3_000_000), proposal.queuedAmount)
        assertEquals(UInt256.ZERO, proposal.balanceAfter)
        assertEquals(listOf(4L), world.queue.requests.value.map { it.nonce })
    }

    @Test
    fun aProposalThatCanNeverWorkIsRefusedBeforeAnythingIsSigned() = runTest {
        val usdcAsset = TransferAsset.Erc20Token(usdc)
        assertEquals(
            SafeTxProposing.ToZeroAddress,
            propose(world.safe, Address.ZERO, TransferAsset.Native, UInt256.ONE),
        )
        assertEquals(SafeTxProposing.ToTokenContract, propose(world.safe, usdc.address, usdcAsset, UInt256.ONE))
        assertEquals(SafeTxProposing.NothingToSend, propose(world.safe, world.recipient, usdcAsset, UInt256.ZERO))
        assertEquals(
            SafeTxProposing.InsufficientFunds,
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.of(2_000_000_000_000_000_000)),
        )

        world.inputs.simulation = Simulation.Succeeded("0".repeat(64), 101)
        assertEquals(SafeTxProposing.Reverts(101), propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))
        world.inputs.simulation = Simulation.Reverted(102)
        assertEquals(SafeTxProposing.Reverts(102), propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))

        world.tokens = TokenBalanceReading.NotAToken
        assertEquals(SafeTxProposing.NotAToken, propose(world.safe, world.recipient, usdcAsset, UInt256.ONE))
        world.tokens = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
        world.kind.value = KindReading.Verified(AddressKind.Eoa, 100)
        assertEquals(
            SafeTxProposing.Refused(SafeTxRefusal.NotASafe),
            propose(world.safe, world.recipient, usdcAsset, UInt256.ONE),
        )
    }

    @Test
    fun anEngineThatWillNotRunACallFromTheSafeLeavesItNotSimulatedAndSaysSo() = runTest {
        world.inputs.simulation = Simulation.Refused

        val proposal = assertIs<SafeTxProposing.Proposed>(
            propose(world.safe, world.recipient, TransferAsset.Native, UInt256.ONE),
        ).proposal

        assertEquals(SafeCallSimulation.NotRun, proposal.simulation)
    }

    @Test
    fun theQueueShowsWhatTheSafeHasNotPassedInNonceOrder() = runTest {
        world.queue.save(world.request(usdcTransfer(6)))
        world.queue.save(world.request(usdcTransfer(4)))
        world.queue.save(world.request(usdcTransfer(3)))

        val reading = assertIs<SafeTxQueueReading.Loaded>(
            ObserveSafeTxQueue(world.queue, world.observeSafe, world.recovery, Network.SEPOLIA)(world.safe).first(),
        )

        assertEquals(4, reading.safeNonce)
        assertEquals(listOf(4L, 6L), reading.transactions.map { it.request.nonce })

        assertTrue(RemoveQueuedSafeTx(world.queue)(world.request(usdcTransfer(6)).hash))
        assertEquals(listOf(4L, 3L), world.queue.requests.value.map { it.nonce }, "6 removed; 3 left to a prune")
    }

    @Test
    fun aRequestIsSharedAsTheCodecWritesIt() {
        val request = world.request(usdcTransfer(4), world.carol)
        val text = EncodeSigningRequest(world.codec)(request)

        assertEquals(SigningRequestDecoding.Decoded(request), world.codec.decode(text))
    }
}
