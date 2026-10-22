package eth.serotine.data.engine

import eth.serotine.data.logs.MemoryDebugLog
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.LogLevel
import eth.serotine.domain.model.LogRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class LoggedChainTest {

    private val engine = FakeEngineChain()
    private val log = MemoryDebugLog()
    private val time = TestTimeSource()
    private val chain = LoggedChain(engine, log, time)

    private val account = Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2")

    private suspend fun records(): List<LogRecord> = log.observeRecords().first()

    @Test
    fun atInfoARequestIsItsNameItsResultAndHowLongItTook() = runTest {
        engine.base.proofs[account] = ProvenBalance("1000", 9_412_118)
        engine.takes = { time += 34.milliseconds }

        assertEquals(ProvenBalance("1000", 9_412_118), chain.proveBalance(account))
        chain.proveBalance(Address.of("0x" + "11".repeat(20)))

        val records = records()
        assertEquals(listOf("requestAccount: ok, 34 ms", "requestAccount: failed, 34 ms"), records.map { it.message })
        assertTrue(records.all { it.level == LogLevel.INFO && it.source == LoggedChain.SOURCE })
    }

    @Test
    fun atDebugTheParametersAndTheResponseComeFirst() = runTest {
        log.setLevel(LogLevel.DEBUG)
        engine.base.proofs[account] = ProvenBalance("1000", 9_412_118)

        chain.proveBalance(account)

        val (detail, line) = records()
        assertEquals(LogLevel.DEBUG, detail.level)
        assertEquals(
            "requestAccount(address=${account.hex}) → ProvenBalance(wei=1000, blockNumber=9412118)",
            detail.message,
        )
        assertEquals("requestAccount: ok, 0 ms", line.message)
    }

    @Test
    fun aRefusalIsAFailureWithItsReasonAndARevertAnAnswer() = runTest {
        engine.transacting.call = CallOutcome.Refused
        chain.callFrom(account, account, "0", byteArrayOf(1))
        engine.transacting.call = CallOutcome.Reverted(7)
        chain.callFrom(account, account, "0", byteArrayOf(1))
        engine.transacting.estimate = EstimateAnswer.Reverted("08c379a0")
        chain.estimateGas(account, account, "0", byteArrayOf(1))
        engine.transacting.send = SendAnswer.Rejected("nonce too low")
        chain.sendRaw(byteArrayOf(2))

        assertEquals(
            listOf(
                "callDetailed: failed (refused), 0 ms",
                "callDetailed: ok, 0 ms",
                "estimateGasDetailed: ok, 0 ms",
                "sendRawTransactionDetailed: failed (rejected: nonce too low), 0 ms",
            ),
            records().map { it.message },
        )
    }

    @Test
    fun aLogThatRecordsNothingCostsTheRequestNothing() = runTest {
        log.setRecording(false)
        engine.base.proofs[account] = ProvenBalance("1000", 9_412_118)

        assertEquals(ProvenBalance("1000", 9_412_118), chain.proveBalance(account))

        assertTrue(records().isEmpty())
    }

    @Test
    fun anExceptionIsRecordedAndThrownOn() = runTest {
        log.setLevel(LogLevel.DEBUG)
        engine.transacting.failure = IllegalStateException("engine gone")

        assertFailsWith<IllegalStateException> { chain.pendingNonce(account) }

        val (detail, line) = records()
        val thrown = IllegalStateException("engine gone")
        assertEquals("getTransactionCount(address=${account.hex}, block=pending) → threw $thrown", detail.message)
        assertEquals("getTransactionCount: failed (IllegalStateException), 0 ms", line.message)
    }

    @Test
    fun aLongResponseIsCut() = runTest {
        log.setLevel(LogLevel.DEBUG)
        engine.transacting.feeHistory = "x".repeat(LoggedChain.MAX_DETAIL + 10)

        chain.feeHistory(4, doubleArrayOf(50.0))

        val detail = records().first().message
        assertTrue(detail.startsWith("feeHistory(blocks=4, block=latest, percentiles=[50.0]) → xxx"), detail)
        assertTrue(detail.endsWith("… (${LoggedChain.MAX_DETAIL + 10} characters)"), detail)
    }

    @Test
    fun aBlockReadIsLoggedByItsNumber() = runTest {
        log.setLevel(LogLevel.DEBUG)
        engine.base.blocks[11_968_920] = """{"number":"0xb6a198"}"""

        chain.blockByNumber(11_968_920)
        chain.blockByNumber(11_968_921)

        val messages = records().map { it.message }
        assertTrue("getBlockByNumber: ok, 0 ms" in messages, "$messages")
        assertTrue("getBlockByNumber: failed, 0 ms" in messages, "$messages")
        assertTrue(messages.any { it.startsWith("getBlockByNumber(block=0xb6a198, full=false) → ") }, "$messages")
    }

    @Test
    fun aStatusReadFailsOnlyWithoutAnEngine() = runTest {
        chain.snapshot()
        engine.base.snapshot = EngineSnapshot.Down
        chain.snapshot()

        assertEquals(listOf("status: ok, 0 ms", "status: failed (no engine), 0 ms"), records().map { it.message })
    }
}

/** An [EngineChain] over a [FakeTransactingChain] and a [FakeLogIndexChain], which share [base]. */
internal class FakeEngineChain(
    val base: FakeChain = FakeChain(),
    val transacting: FakeTransactingChain = FakeTransactingChain(base),
    val index: FakeLogIndexChain = FakeLogIndexChain(base),
) : EngineChain, TransactingChain by transacting, LogIndexChain by index {
    /** What each balance proof does before it answers: a test's time passing. */
    var takes: () -> Unit = {}

    override fun snapshot(): EngineSnapshot = base.snapshot()

    override fun proveBalance(address: Address): ProvenBalance? {
        takes()
        return base.proveBalance(address)
    }

    override fun proveCode(address: Address): ProvenCode? = base.proveCode(address)

    override fun call(to: Address, data: ByteArray): CallOutcome? = base.call(to, data)

    override fun proveSlot(address: Address, slot: Long, holder: Address?): ProvenSlot? =
        base.proveSlot(address, slot, holder)

    override fun storageAt(address: Address, slot: ByteArray): String? = base.storageAt(address, slot)

    override fun blockByNumber(number: Long): String? = base.blockByNumber(number)
}
