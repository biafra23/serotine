package eth.serotine.data.engine

import eth.serotine.data.engine.AddressActivity.Kind
import eth.serotine.data.keys.toHex
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenUnits
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class AddressActivityTest {

    private val a = Address.of("0x" + "aa".repeat(20))
    private val b = Address.of("0x" + "bb".repeat(20))

    /**
     * An engine follower whose report a test sets, recording what it is asked. Its `base` names
     * the block asked after, with [hashes]' hash for it (a test changes one to model a reorg).
     */
    internal class FakeActivity : ActivityChain {
        var report: String? = null
        var takes = true
        val pushes = mutableListOf<String>()
        val asked = mutableListOf<Long>()
        val hashes = mutableMapOf<Long, String>()

        fun hashOf(block: Long) = hashes[block] ?: ("0x" + block.toString(16).padStart(64, '0'))

        override fun setActivityWatch(configJson: String): Boolean {
            pushes += configJson
            return takes
        }

        override fun addressActivity(afterBlock: Long): String? {
            asked += afterBlock
            return report?.replace(BASE, """{"block":$afterBlock,"hash":"${hashOf(afterBlock)}"}""")
        }

        companion object {
            const val BASE = "BASE"
        }
    }

    private fun report(
        to: Long,
        from: Long = to - 299,
        accountGaps: String = "[[$from,$to]]",
        logGaps: String = "[]",
        touched: String = "",
        watching: List<Address>,
    ) = """{"from":$from,"to":$to,"base":${FakeActivity.BASE},""" +
        """"gaps":{"account":$accountGaps,"log":$logGaps},"touched":[$touched],""" +
        """"watching":[${watching.joinToString(",") { "\"${it.hex}\"" }}]}"""

    private fun touch(address: Address, block: Long) =
        """{"address":"${address.hex}","block":$block,"blockHash":"0x${"11".repeat(32)}","via":["log"]}"""

    @Test
    fun anAddressNotWatchedYetIsWatchedAndNotVouchedFor() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = emptyList()) }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        assertEquals(listOf("""{"watch":["${a.hex}"]}"""), engine.pushes)
    }

    @Test
    fun aCoveredUntouchedRangeVouchesForAPinnedProof() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500), "a proof whose block was never pinned")
        activity.pin(490)
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, 500))
    }

    @Test
    fun aTouchAfterTheProofsBlockIsAChangeOneAtItIsInTheProof() {
        val engine = FakeActivity().apply {
            report = report(to = 500, touched = touch(a, 490) + "," + touch(b, 495), watching = listOf(a, b))
        }
        val activity = AddressActivity(engine).apply { pin(490) }

        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, 500), "the proof already holds block 490's effect")
        assertFalse(activity.unchangedSince(b, Kind.LOG, 490, 500))
    }

    @Test
    fun aReorgThatReplacedTheProofsBlockIsAChangeThoughNothingTouchesTheAddress() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine).apply { pin(490) }
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, 500))

        // Block 490 was replaced: the dropped one may have touched the address, and the report
        // lists only the canonical chain's touches. Its hash is what says so.
        engine.hashes[490] = "0x" + "ee".repeat(32)
        engine.report = report(to = 501, watching = listOf(a))
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 501))
    }

    @Test
    fun aBlockTheEngineDoesNotHoldYetCannotBePinned() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        val held = engine.report
        engine.report = null // the follower has not got the block yet
        activity.pin(490)
        engine.report = held
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
    }

    @Test
    fun anAddressNobodyAsksAboutIsUnwatchedAndARefusedPushWaits() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = emptyList()) }
        val activity = AddressActivity(engine)
        engine.takes = false
        activity.unchangedSince(a, Kind.LOG, 490, 500)
        activity.unchangedSince(a, Kind.LOG, 490, 501)
        assertEquals(1, engine.pushes.size, "a refused push is not repeated at every poll")

        engine.takes = true
        engine.report = report(to = 600, watching = listOf(a, b))
        activity.unchangedSince(b, Kind.LOG, 590, 600)
        // a was last asked at head 501; at head 900 it has been idle for more than 300 heads.
        engine.report = report(to = 900, watching = listOf(a, b))
        activity.unchangedSince(b, Kind.LOG, 890, 900)
        assertTrue(engine.pushes.contains("""{"unwatch":["${a.hex}"]}"""), engine.pushes.toString())
    }

    @Test
    fun aReportWhoseNewestBlockIsAGapIsReadOnceMore() {
        val clock = TestTimeSource()
        val engine = FakeActivity().apply { report = report(to = 500, logGaps = "[[500,500]]", watching = listOf(a)) }
        val activity = AddressActivity(engine, clock).apply { pin(490) }
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        val reports = { engine.asked.count { it == 200L } }
        assertEquals(1, reports())

        // The follower caught up meanwhile; within 2 s the cached report stands.
        engine.report = report(to = 500, watching = listOf(a))
        activity.unchangedSince(a, Kind.LOG, 490, 500)
        assertEquals(1, reports())
        clock += 3.seconds
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, 500))
        assertEquals(2, reports())
        // Once more only, at one head.
        clock += 3.seconds
        activity.unchangedSince(a, Kind.LOG, 490, 500)
        assertEquals(2, reports())
    }

    @Test
    fun accountCoverageIsKnownOnlyFromAReportThatHasSome() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine)
        assertFalse(activity.hasAccountCoverage(500), "every block an account gap")
        engine.report = report(to = 501, accountGaps = "[]", watching = listOf(a))
        assertTrue(activity.hasAccountCoverage(501))
        assertFalse(AddressActivity(null).hasAccountCoverage(501), "no engine")
    }

    @Test
    fun aGapOfTheFactsKindAnywhereInTheRangeIsNoVouching() {
        val engine = FakeActivity().apply {
            report = report(to = 500, logGaps = "[[495,496]]", watching = listOf(a))
        }
        val activity = AddressActivity(engine).apply { pin(490); pin(497) }

        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        assertTrue(activity.unchangedSince(a, Kind.LOG, 497, 500))
        // The account kind is a gap everywhere in this report.
        assertFalse(activity.unchangedSince(a, Kind.ACCOUNT, 497, 500))
    }

    @Test
    fun aProofOlderThanTheReportOrAHeadPastItIsNoVouching() {
        val engine = FakeActivity().apply { report = report(to = 500, from = 300, watching = listOf(a)) }
        val activity = AddressActivity(engine)

        assertFalse(activity.unchangedSince(a, Kind.LOG, 299, 500))
        // A head past the report asks for a new one; the same answer still stops at 500.
        assertFalse(activity.unchangedSince(a, Kind.LOG, 400, 501))
        assertFalse(activity.unchangedSince(a, Kind.LOG, -1, 500), "a block the engine did not name")
    }

    @Test
    fun oneReportServesEveryReadingAtItsHead() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a, b)) }
        val activity = AddressActivity(engine)

        activity.unchangedSince(a, Kind.LOG, 490, 500)
        activity.unchangedSince(b, Kind.LOG, 490, 500)
        activity.unchangedSince(a, Kind.ACCOUNT, 490, 499)
        assertEquals(listOf(200L), engine.asked)

        engine.report = report(to = 501, watching = listOf(a, b))
        activity.unchangedSince(a, Kind.LOG, 490, 501)
        assertEquals(listOf(200L, 201L), engine.asked)
    }

    @Test
    fun anErrorOrNoAnswerIsNoVouching() {
        val engine = FakeActivity().apply { report = """{"error":"handle is paused"}""" }
        val activity = AddressActivity(engine)
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))

        engine.report = null
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        engine.report = "not json"
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        assertFalse(AddressActivity(null).unchangedSince(a, Kind.LOG, 490, 500), "no engine")
    }

    @Test
    fun anEngineThatForgotTheListIsToldAgain() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }
        val activity = AddressActivity(engine).apply { pin(490) }
        assertTrue(activity.unchangedSince(a, Kind.LOG, 490, 500))

        // A restarted engine: its next report lists nobody.
        engine.report = report(to = 501, watching = emptyList())
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 501))
        assertEquals(listOf("""{"watch":["${a.hex}"]}"""), engine.pushes)
    }

    @Test
    fun aTokenBalanceVouchedForIsNotCalledAgainAndKeepsItsBlock() = runTest {
        val holder = a
        val usdc = Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238")
        val calldata = Abi.balanceOf(holder).toHex()
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, watching = listOf(holder)) }
        val balances = VerifiedTokenBalances(
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
            gated = setOf(usdc),
        )
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "1", blockNumber = 9)
        assertEquals(TokenBalanceReading.Verified(TokenUnits.of("1"), 9), balances.observeBalance(holder, usdc).first())

        // A new head, nothing touched the holder: the proof at block 9 stands, though a new call
        // would now answer 2.
        chain.snapshot = running(head = 10)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "2", blockNumber = 10)
        engine.report = report(to = 10, watching = listOf(holder))
        assertEquals(TokenBalanceReading.Verified(TokenUnits.of("1"), 9), balances.observeBalance(holder, usdc).first())

        // A log at block 11 may name the holder: the balance is called again.
        chain.snapshot = running(head = 11)
        chain.calls[usdc to calldata] = CallOutcome.Returned("0".repeat(63) + "3", blockNumber = 11)
        engine.report = report(to = 11, touched = touch(holder, 11), watching = listOf(holder))
        assertEquals(TokenBalanceReading.Verified(TokenUnits.of("3"), 11), balances.observeBalance(holder, usdc).first())
    }

    @Test
    fun aTokenOutsideTheGatedSetIsCalledAtEveryHead() = runTest {
        val holder = a
        val custom = Address.of("0x" + "c0".repeat(20))
        val calldata = Abi.balanceOf(holder).toHex()
        val chain = FakeChain().apply { snapshot = running(head = 9) }
        val engine = FakeActivity().apply { report = report(to = 9, watching = listOf(holder)) }
        val balances = VerifiedTokenBalances(
            chain,
            StandardTestDispatcher(testScheduler),
            4.seconds,
            activity = AddressActivity(engine),
            gated = emptySet(),
        )
        chain.calls[custom to calldata] = CallOutcome.Returned("0".repeat(63) + "1", blockNumber = 9)
        balances.observeBalance(holder, custom).first()
        // A rebasing or interest-bearing token may change with no log naming the holder.
        chain.snapshot = running(head = 10)
        chain.calls[custom to calldata] = CallOutcome.Returned("0".repeat(63) + "2", blockNumber = 10)
        engine.report = report(to = 10, watching = listOf(holder))
        assertEquals(TokenBalanceReading.Verified(TokenUnits.of("2"), 10), balances.observeBalance(holder, custom).first())
    }
}
