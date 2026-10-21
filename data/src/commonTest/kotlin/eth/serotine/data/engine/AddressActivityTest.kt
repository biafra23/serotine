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

@OptIn(ExperimentalCoroutinesApi::class)
class AddressActivityTest {

    private val a = Address.of("0x" + "aa".repeat(20))
    private val b = Address.of("0x" + "bb".repeat(20))

    /** An engine follower whose report a test sets, recording what it is asked. */
    private class FakeActivity : ActivityChain {
        var report: String? = null
        var takes = true
        val pushes = mutableListOf<String>()
        val asked = mutableListOf<Long>()

        override fun setActivityWatch(configJson: String): Boolean {
            pushes += configJson
            return takes
        }

        override fun addressActivity(afterBlock: Long): String? {
            asked += afterBlock
            return report
        }
    }

    private fun report(
        to: Long,
        from: Long = to - 299,
        accountGaps: String = "[[$from,$to]]",
        logGaps: String = "[]",
        touched: String = "",
        watching: List<Address>,
    ) = """{"from":$from,"to":$to,"base":{"block":${from - 1},"hash":null},""" +
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
    fun aCoveredUntouchedRangeVouchesForTheProof() {
        val engine = FakeActivity().apply { report = report(to = 500, watching = listOf(a)) }

        assertTrue(AddressActivity(engine).unchangedSince(a, Kind.LOG, 490, 500))
    }

    @Test
    fun aTouchFromTheProofsOwnBlockOnIsAChange() {
        val engine = FakeActivity().apply {
            report = report(to = 500, touched = touch(a, 490) + "," + touch(b, 495), watching = listOf(a, b))
        }
        val activity = AddressActivity(engine)

        // Its own block too: a reorg that replaced it would show as a touch there.
        assertFalse(activity.unchangedSince(a, Kind.LOG, 490, 500))
        assertTrue(activity.unchangedSince(a, Kind.LOG, 491, 500))
        assertFalse(activity.unchangedSince(b, Kind.LOG, 491, 500))
    }

    @Test
    fun aGapOfTheFactsKindAnywhereInTheRangeIsNoVouching() {
        val engine = FakeActivity().apply {
            report = report(to = 500, logGaps = "[[495,496]]", watching = listOf(a))
        }
        val activity = AddressActivity(engine)

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
        val activity = AddressActivity(engine)
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
}
