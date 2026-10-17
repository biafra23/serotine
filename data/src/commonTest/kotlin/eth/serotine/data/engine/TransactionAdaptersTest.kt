package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.Broadcast
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.domain.repository.FeeReading
import eth.serotine.domain.repository.GasEstimate
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NonceReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.Simulation
import eth.serotine.domain.repository.TransactionProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionAdaptersTest {

    private val chain = FakeTransactingChain()
    private val alice = Address.of("0x" + "aa".repeat(20))
    private val bob = Address.of("0x" + "bb".repeat(20))

    private fun TestScope.inputs() = EngineTransactionInputs(chain, StandardTestDispatcher(testScheduler))

    private fun synced(finalized: Long = 0) {
        chain.base.snapshot = running(head = 100).copy(finalizedBlock = finalized)
    }

    @Test
    fun aFeeHistoryBecomesLevelsFromTheMedianOfEachPercentile() {
        val json = """
            {"oldestBlock":"0x5a","baseFeePerGas":["0x10","0x12","0x14","0x16"],
             "gasUsedRatio":[0.5,0.6,0.7],
             "reward":[["0x1","0x5","0x9"],["0x3","0x2","0x20"],["0x2","0x7","0x8"]]}
        """.trimIndent()

        val quote = EngineJson.feeQuote(json)!!

        assertEquals(UInt256.of(0x16), quote.nextBaseFee, "the base fee after the newest block")
        assertEquals(0x5aL + 2, quote.blockNumber)
        // Medians 2, 5 and 9, each raised to the 0.001 gwei floor; a level never below the slower one.
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.SLOW))
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.NORMAL))
        assertEquals(EngineJson.MIN_TIP, quote.tip(FeeLevel.FAST))
        assertEquals(UInt256.of(0x16 * 2) + EngineJson.MIN_TIP, quote.maxFeePerGas(FeeLevel.FAST))
    }

    @Test
    fun aFeeHistoryWithRealTipsKeepsThemInOrder() {
        val gwei = 1_000_000_000L
        fun hex(n: Long) = "\"0x" + n.toString(16) + "\""
        val json = """{"oldestBlock":"0x1","baseFeePerGas":[${hex(gwei)},${hex(gwei)}],
            "reward":[[${hex(3 * gwei)},${hex(2 * gwei)},${hex(5 * gwei)}]]}"""

        val quote = EngineJson.feeQuote(json)!!

        assertEquals(UInt256.of(3 * gwei), quote.tip(FeeLevel.SLOW))
        assertEquals(UInt256.of(3 * gwei), quote.tip(FeeLevel.NORMAL), "raised to the slower level's")
        assertEquals(UInt256.of(5 * gwei), quote.tip(FeeLevel.FAST))
    }

    @Test
    fun aMalformedFeeHistoryIsNoAnswer() {
        assertNull(EngineJson.feeQuote("""{"oldestBlock":"0x1","baseFeePerGas":["12"]}"""))
        assertNull(EngineJson.feeQuote("""{"baseFeePerGas":["0x1"]}"""))
        assertNull(EngineJson.feeQuote("not json"))
    }

    @Test
    fun aReceiptSaysItsBlockAndWhetherItSucceeded() {
        assertEquals(
            EngineJson.Receipt(0x1234, succeeded = true),
            EngineJson.receipt("""{"blockNumber":"0x1234","status":"0x1"}"""),
        )
        assertEquals(
            EngineJson.Receipt(7, succeeded = false),
            EngineJson.receipt("""{"blockNumber":"0x7","status":"0x0"}"""),
        )
        assertNull(EngineJson.receipt("null"))
    }

    @Test
    fun inputsAreAskedOnlyWhileReadsCanBeVerified() = runTest {
        chain.base.snapshot = EngineSnapshot.Booting
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().nonce(alice))
        chain.base.snapshot = EngineSnapshot.Down
        assertEquals(FeeReading.Unavailable(ReadUnavailable.NO_ENGINE), inputs().fees())
        assertEquals(
            NonceReading.Unavailable(ReadUnavailable.NO_ENGINE),
            EngineTransactionInputs(null, StandardTestDispatcher(testScheduler)).nonce(alice),
        )

        synced()
        chain.nonce = 7
        assertEquals(NonceReading.Read(7), inputs().nonce(alice))
    }

    @Test
    fun anEstimateAndASimulationSayWhatTheEngineFound() = runTest {
        synced()
        val value = UInt256.of(5)
        chain.estimate = EstimateAnswer.Gas(21_000)
        assertEquals(GasEstimate.Estimated(21_000), inputs().estimateGas(alice, bob, value, ByteArray(0)))
        chain.estimate = EstimateAnswer.Reverted("08c379a0")
        assertEquals(GasEstimate.Reverted("08c379a0"), inputs().estimateGas(alice, bob, value, ByteArray(0)))
        chain.estimate = EstimateAnswer.Infeasible("insufficient funds for transfer")
        assertEquals(
            GasEstimate.Infeasible("insufficient funds for transfer"),
            inputs().estimateGas(alice, bob, value, ByteArray(0)),
        )

        chain.call = CallOutcome.Returned("01", 99)
        assertEquals(Simulation.Succeeded("01", 99), inputs().simulate(alice, bob, value, ByteArray(0)))
        chain.call = CallOutcome.Reverted(99)
        assertEquals(Simulation.Reverted(99), inputs().simulate(alice, bob, value, ByteArray(0)))
        chain.call = null
        assertEquals(
            Simulation.Unavailable(ReadUnavailable.UNPROVEN),
            inputs().simulate(alice, bob, value, ByteArray(0)),
        )
    }

    @Test
    fun anEngineThatFailsHasNoAnswerNotACrash() = runTest {
        synced()
        chain.failure = Error("native library broken")
        assertEquals(NonceReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().nonce(alice))
        // A CancellationException the engine's own code throws is a failure, not the end of the caller.
        chain.failure = CancellationException("thrown by the engine")
        assertEquals(FeeReading.Unavailable(ReadUnavailable.UNPROVEN), inputs().fees())
    }

    @Test
    fun theBroadcastVerdictIsPassedOn() = runTest {
        val broadcaster = EngineBroadcaster(chain, StandardTestDispatcher(testScheduler))
        chain.send = SendAnswer.Sent("0x" + "ab".repeat(32))
        assertEquals(Broadcast.Sent("0x" + "ab".repeat(32)), broadcaster.send(byteArrayOf(2, 1)))
        chain.send = SendAnswer.Rejected("nonce too low: next nonce 8, tx nonce 7")
        assertEquals(Broadcast.Rejected("nonce too low: next nonce 8, tx nonce 7"), broadcaster.send(byteArrayOf(2)))
        chain.failure = Error("native library broken")
        assertEquals(Broadcast.Unavailable("the light client failed (Error)"), broadcaster.send(byteArrayOf(2)))
        assertEquals(
            Broadcast.Unavailable("no light client on this device"),
            EngineBroadcaster(null, StandardTestDispatcher(testScheduler)).send(byteArrayOf(2)),
        )
    }

    @Test
    fun aReceiptIsWatchedFromPendingToIncludedToFinalizedAndThenNoMore() = runTest {
        synced(finalized = 50)
        val receipts = EngineReceipts(chain, StandardTestDispatcher(testScheduler), 4.seconds)
        val seen = mutableListOf<TransactionProgress>()
        val watching = launch { receipts.observe("0x" + "cd".repeat(32)).toList(seen) }

        chain.receipt = "null"
        runCurrent()
        chain.receipt = """{"blockNumber":"0x60","status":"0x1"}"""
        advanceTimeBy(4_001)
        synced(finalized = 0x60)
        advanceTimeBy(4_001)
        advanceTimeBy(8_001)

        assertEquals(
            listOf(
                TransactionProgress.Pending,
                TransactionProgress.Included(0x60, succeeded = true),
                TransactionProgress.Finalized(0x60, succeeded = true),
            ),
            seen,
        )
        assertEquals(true, watching.isCompleted, "finalized: nothing more to watch")
    }

    @Test
    fun aReceiptThatCannotBeReadIsNotVerifiedForThatPoll() = runTest {
        synced()
        val receipts = EngineReceipts(chain, StandardTestDispatcher(testScheduler), 4.seconds)
        val seen = mutableListOf<TransactionProgress>()
        val watching = launch { receipts.observe("0x" + "cd".repeat(32)).toList(seen) }

        chain.receipt = null
        runCurrent()
        chain.receipt = "null"
        advanceTimeBy(4_001)

        assertEquals(
            listOf(TransactionProgress.Unavailable(ReadUnavailable.UNPROVEN), TransactionProgress.Pending),
            seen,
        )
        watching.cancel()
    }

    @Test
    fun aNameIsTakenOnlyAsFinalizedStateVerifiesIt() = runTest {
        synced()
        val names = EngineNames(chain, StandardTestDispatcher(testScheduler))
        chain.name = NameAnswer(bob.hex, 90, verified = true, error = null)
        assertEquals(NameResolution.Resolved("serotine.eth", bob, 90), names.resolve(" Serotine.ETH "))
        assertEquals(listOf("serotine.eth"), chain.resolved, "lower-cased before it is asked")

        chain.name = NameAnswer(bob.hex, 90, verified = false, error = null)
        assertEquals(NameResolution.Unavailable(ReadUnavailable.UNPROVEN), names.resolve("serotine.eth"))
        chain.name = NameAnswer(null, 90, verified = true, error = null)
        assertEquals(NameResolution.NoAddress, names.resolve("serotine.eth"))
        chain.name = NameAnswer(null, -1, verified = false, error = "no snap peer")
        assertEquals(NameResolution.Unavailable(ReadUnavailable.UNPROVEN), names.resolve("serotine.eth"))

        assertEquals(NameResolution.NotAName, names.resolve("serotine"))
        assertEquals(NameResolution.NotAName, names.resolve("a..eth"))
        assertEquals(NameResolution.NotAName, names.resolve("my name.eth"))
    }
}
