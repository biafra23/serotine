package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.Broadcaster
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeQuote
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Signer
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import eth.serotine.domain.repository.TransactionInputs
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransfersTest {

    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))
    private val usdc = Token(Address.of("0x" + "cc".repeat(20)), "USDC", "USD Coin", 6, icon = null)
    private val gwei = 1_000_000_000L

    private val inputs = FakeInputs()
    private var native: BalanceReading = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
    private var token: TokenBalanceReading = TokenBalanceReading.Verified(TokenUnits.of("5000000"), 100)
    private val balances = object : NativeBalances {
        override fun observeBalance(address: Address): Flow<BalanceReading> = flowOf(native)
    }
    private val tokens = object : TokenBalances {
        override fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading> =
            flowOf(this@TransfersTest.token)
    }
    private val quoteTransfer = QuoteTransfer(inputs, balances, tokens, Network.SEPOLIA)

    @Test
    fun anAddressIsTakenAsIsAndANameIsResolved() = runTest {
        val names = object : NameResolver {
            override suspend fun resolve(name: String): NameResolution =
                if (name == "bob.eth") NameResolution.Resolved("bob.eth", bob, 77) else NameResolution.NoAddress
        }
        val resolve = ResolveRecipient(names)

        assertEquals(RecipientLookup.Found(bob, null, null), resolve(" ${bob.hex} "))
        assertEquals(RecipientLookup.Found(bob, "bob.eth", 77), resolve("bob.eth"))
        assertEquals(RecipientLookup.NoAddress, resolve("nobody.eth"))
        assertEquals(RecipientLookup.NotAnAddress, resolve("bob"))
        assertEquals(RecipientLookup.NotAnAddress, resolve("0x1234"))
    }

    @Test
    fun aTypedAddressMustCarryItsChecksumRightAndANameMayStartWith0x() = runTest {
        val asked = mutableListOf<String>()
        val names = object : NameResolver {
            override suspend fun resolve(name: String): NameResolution {
                asked += name
                return if (name == "dark.eth") {
                    NameResolution.OffchainOnly
                } else {
                    NameResolution.Resolved(name, bob, 5)
                }
            }
        }
        val resolve = ResolveRecipient(names)
        val vector = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" // EIP-55's own example

        assertEquals(RecipientLookup.Found(Address.of(vector), null, null), resolve(vector))
        assertEquals(RecipientLookup.Found(Address.of(vector), null, null), resolve(vector.lowercase()))
        assertEquals(RecipientLookup.ChecksumMismatch, resolve(vector.replace("aAeb", "aaEb")))
        assertEquals(RecipientLookup.Found(bob, "0xfoo.eth", 5), resolve("0xfoo.eth"))
        assertEquals(RecipientLookup.OffchainOnly, resolve("dark.eth"))
        assertEquals(listOf("0xfoo.eth", "dark.eth"), asked)
    }

    @Test
    fun aNativeTransferIsQuotedFromVerifiedInputs() = runTest {
        val amount = UInt256.of(100_000_000_000_000_000L) // 0.1 ETH

        val quoting = quoteTransfer(alice, bob, TransferAsset.Native, amount, FeeLevel.NORMAL)
        val quote = assertIs<TransferQuoting.Quoted>(quoting).quote

        val tx = quote.transaction
        assertEquals(Network.SEPOLIA.chainId, tx.chainId)
        assertEquals(7, tx.nonce)
        assertEquals(bob, tx.to)
        assertEquals(amount, tx.value)
        assertContentEquals(ByteArray(0), tx.data)
        assertEquals(21_000, tx.gasLimit)
        assertEquals(UInt256.of(2 * gwei), tx.maxPriorityFeePerGas)
        assertEquals(UInt256.of(2 * 10 * gwei + 2 * gwei), tx.maxFeePerGas, "twice the next base fee plus the tip")
        assertEquals(UInt256.of(21_000L * 22 * gwei), quote.maxFee)
        assertEquals(UInt256.of(1_000_000_000_000_000_000L) - amount - quote.maxFee, quote.nativeAfterAtLeast)
        assertEquals(listOf(Triple(alice, bob, amount)), inputs.simulated.map { Triple(it.first, it.second, it.third) })
    }

    @Test
    fun aTokenTransferCallsItsContract() = runTest {
        inputs.gas = 51_000
        val amount = UInt256.of(1_500_000) // 1.5 USDC

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), amount, FeeLevel.FAST),
        ).quote

        assertEquals(usdc.address, quote.transaction.to)
        assertEquals(UInt256.ZERO, quote.transaction.value)
        assertContentEquals(Erc20.transferData(bob, amount), quote.transaction.data)
        assertEquals(UInt256.of(5_000_000), quote.tokenBefore)
        assertEquals(UInt256.of(3_500_000), quote.tokenAfter)
        assertEquals(100, quote.tokenBalanceBlock)
    }

    @Test
    fun whatTheSenderCannotPayIsRefusedBeforeAnythingIsSigned() = runTest {
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(2_000_000_000_000_000_000L), FeeLevel.SLOW),
        )
        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(6_000_000), FeeLevel.SLOW),
        )
        // The whole balance as the amount leaves nothing for the fee.
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.of(1_000_000_000_000_000_000L), FeeLevel.SLOW),
        )
    }

    @Test
    fun anEstimateBelowTheIntrinsicGasIsRaisedToIt() = runTest {
        inputs.gas = 17_433 // what the Rust engine estimates for a zero-value transfer

        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote

        assertEquals(21_000, quote.transaction.gasLimit)
    }

    @Test
    fun aCheapRefusalAsksTheChainNothingMore() = runTest {
        assertEquals(
            TransferQuoting.InsufficientToken,
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.of(6_000_000), FeeLevel.SLOW),
        )
        assertEquals(0, inputs.nonces + inputs.feeHistories, "no nonce, no fee history for a transfer refused before")
    }

    @Test
    fun anInfeasibleEstimateIsSaidAsTheEvmSaysIt() = runTest {
        inputs.estimate = GasEstimate.Infeasible("insufficient funds for transfer")
        assertEquals(
            TransferQuoting.InsufficientFunds,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        inputs.estimate = GasEstimate.Infeasible("max initcode size exceeded")
        assertEquals(
            TransferQuoting.Infeasible("max initcode size exceeded"),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aRecipientThatCanNeverGiveItBackIsRefused() = runTest {
        val names = object : NameResolver {
            override suspend fun resolve(name: String): NameResolution = NameResolution.Resolved(name, Address.ZERO, 77)
        }
        val resolve = ResolveRecipient(names)
        assertEquals(RecipientLookup.ZeroAddress, resolve("0x" + "00".repeat(20)))
        assertEquals(RecipientLookup.NoAddress, resolve("burn.eth"), "ENS's zero address is no address set")

        assertEquals(
            TransferQuoting.ToZeroAddress,
            quoteTransfer(alice, Address.ZERO, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        assertEquals(
            TransferQuoting.ToTokenContract,
            quoteTransfer(alice, usdc.address, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        assertEquals(0, inputs.simulated.size + inputs.nonces + inputs.feeHistories, "nothing asked of the chain")
    }

    @Test
    fun aZeroAmountIsNothingToSend() = runTest {
        assertEquals(
            TransferQuoting.NothingToSend,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ZERO, FeeLevel.NORMAL),
        )
        assertEquals(0, inputs.simulated.size, "nothing asked of the chain")
    }

    @Test
    fun anAbsurdFeeAnswerIsNoAnswerNotACrash() = runTest {
        // A base fee of 2 × 10^76: the cap fits in 256 bits, times the gas it does not.
        val huge = UInt256.parse("1" + "0".repeat(76))!!
        inputs.fees = FeeReading.Quoted(
            FeeQuote(huge + huge, FeeLevel.entries.associateWith { UInt256.ONE }, blockNumber = 99),
        )

        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun aTransferThatRevertsOrThatTheTokenRefusesStopsAtTheQuote() = runTest {
        inputs.simulation = Simulation.Reverted(100)
        assertEquals(
            TransferQuoting.Reverts(100),
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        // transfer() returning false: refused as surely as a revert.
        inputs.simulation = Simulation.Succeeded("0".repeat(64), 100)
        assertEquals(
            TransferQuoting.Reverts(100),
            quoteTransfer(alice, bob, TransferAsset.Erc20Token(usdc), UInt256.ONE, FeeLevel.NORMAL),
        )
        inputs.estimate = GasEstimate.Reverted("")
        assertEquals(
            TransferQuoting.Reverts(null),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun nothingIsQuotedOnLessThanVerifiedState() = runTest {
        native = BalanceReading.Syncing
        assertEquals(
            TransferQuoting.Syncing,
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        native = BalanceReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.UNPROVEN),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
        native = BalanceReading.Verified(Wei.of("1000000000000000000"), 100)
        inputs.fees = FeeReading.Unavailable(ReadUnavailable.STALE_ANCHOR)
        assertEquals(
            TransferQuoting.Unavailable(ReadUnavailable.STALE_ANCHOR),
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        )
    }

    @Test
    fun whatIsSignedIsTheQuotedTransactionAndItIsBroadcastAsSigned() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 1)
        val signer = FakeSigner(Signing.Signed(signature))
        val broadcaster = FakeBroadcaster(Broadcast.Sent("0x" + "00".repeat(32)))
        val unlock = Unlock.WithPassword(WalletPassword("correct horse battery"))

        val sent = assertIs<TransferSending.Sent>(SendTransfer(signer, broadcaster)(quote, unlock))

        val asked = Triple<Address, Digest, Unlock>(alice, quote.transaction.signingDigest(), unlock)
        assertEquals(listOf(asked), signer.asked)
        val raw = quote.transaction.encodeSigned(signature)
        assertContentEquals(raw, broadcaster.sent.single())
        assertEquals(Eip1559Transaction.hashOf(raw), sent.hash, "the raw bytes' own hash")
    }

    @Test
    fun aSignatureOrABroadcastThatIsRefusedIsSaidAndNothingElseHappens() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val broadcaster = FakeBroadcaster(Broadcast.Rejected("nonce too low"))
        val unlock = Unlock.WithBiometrics("Send 1 wei")

        assertEquals(
            TransferSending.NotSigned(Signing.Cancelled),
            SendTransfer(FakeSigner(Signing.Cancelled), broadcaster)(quote, unlock),
        )
        assertEquals(0, broadcaster.sent.size, "nothing left the device")
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val signed = SignedTransfer(quote.transaction.encodeSigned(signature))
        assertEquals(
            TransferSending.NotSent(Broadcast.Rejected("nonce too low"), signed),
            SendTransfer(FakeSigner(Signing.Signed(signature)), broadcaster)(quote, unlock),
        )
    }

    @Test
    fun aTransferNotSentIsRetriedWithItsOwnBytes() = runTest {
        val quote = assertIs<TransferQuoting.Quoted>(
            quoteTransfer(alice, bob, TransferAsset.Native, UInt256.ONE, FeeLevel.NORMAL),
        ).quote
        val signature = Signature("1".padStart(64, '0'), "2".padStart(64, '0'), recoveryId = 0)
        val unavailable = FakeBroadcaster(Broadcast.Unavailable("no peer"))
        val sending = SendTransfer(FakeSigner(Signing.Signed(signature)), unavailable)
        val notSent = assertIs<TransferSending.NotSent>(sending(quote, Unlock.WithBiometrics("Send 1 wei")))

        val again = FakeBroadcaster(Broadcast.Sent("0x" + "00".repeat(32)))
        val sent = assertIs<TransferSending.Sent>(RebroadcastTransfer(again)(notSent.signed))

        assertContentEquals(unavailable.sent.single(), again.sent.single(), "the very bytes, not a new quote")
        assertEquals(notSent.signed.hash, sent.hash)
    }

    private inner class FakeInputs : TransactionInputs {
        var gas = 21_000L
        var estimate: GasEstimate? = null
        var simulation: Simulation = Simulation.Succeeded("", 100)
        var fees: FeeReading = FeeReading.Quoted(
            FeeQuote(
                nextBaseFee = UInt256.of(10 * gwei),
                tips = mapOf(
                    FeeLevel.SLOW to UInt256.of(1 * gwei),
                    FeeLevel.NORMAL to UInt256.of(2 * gwei),
                    FeeLevel.FAST to UInt256.of(3 * gwei),
                ),
                blockNumber = 99,
            ),
        )
        val simulated = mutableListOf<Triple<Address, Address, UInt256>>()
        var nonces = 0
        var feeHistories = 0

        override suspend fun nonce(account: Address): NonceReading {
            nonces++
            return NonceReading.Read(7)
        }

        override suspend fun fees(): FeeReading {
            feeHistories++
            return fees
        }

        override suspend fun estimateGas(from: Address, to: Address, value: UInt256, data: ByteArray): GasEstimate =
            estimate ?: GasEstimate.Estimated(gas)

        override suspend fun simulate(from: Address, to: Address, value: UInt256, data: ByteArray): Simulation {
            simulated += Triple(from, to, value)
            return simulation
        }
    }

    private class FakeSigner(private val answer: Signing) : Signer {
        val asked = mutableListOf<Triple<Address, Digest, Unlock>>()

        override suspend fun sign(account: Address, digest: Digest, unlock: Unlock): Signing {
            asked += Triple(account, digest, unlock)
            return answer
        }
    }

    private class FakeBroadcaster(private val answer: Broadcast) : Broadcaster {
        val sent = mutableListOf<ByteArray>()

        override suspend fun send(raw: ByteArray): Broadcast {
            sent += raw
            return answer
        }
    }
}
