package eth.serotine.data.engine

import io.myotis.api.CallResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a Myotis call result becomes what the adapters trust: the OK/REVERTED/refused/none split. */
class CallOutcomesTest {

    @Test
    fun anAnswerKeepsItsBytesAndItsBlock() {
        assertEquals(CallOutcome.Returned("00ff", 9), callOutcome(CallResult.ok(byteArrayOf(0, -1)).atBlock(9)))
        assertEquals(CallOutcome.Returned("", null), callOutcome(CallResult.ok(null)))
        // The block's hash when the engine proved it (Myotis ABI 52), lower-cased.
        assertEquals(
            CallOutcome.Returned("01", 9, "0x" + "ab".repeat(32)),
            callOutcome(CallResult.ok(byteArrayOf(1)).atBlock(9, "0x" + "AB".repeat(32))),
        )
        assertEquals(
            CallOutcome.Reverted(9, "0x" + "ab".repeat(32)),
            callOutcome(CallResult.reverted(ByteArray(0)).atBlock(9, "0x" + "ab".repeat(32))),
        )
    }

    @Test
    fun aRevertIsAnAnswer() {
        assertEquals(CallOutcome.Reverted(9), callOutcome(CallResult.reverted(ByteArray(0)).atBlock(9)))
    }

    @Test
    fun aRefusalOrAnInfeasibleCallIsPermanent() {
        assertEquals(CallOutcome.Refused, callOutcome(CallResult.refused("past a fork")))
        assertEquals(CallOutcome.Refused, callOutcome(CallResult.infeasible("calldata too costly")))
    }

    @Test
    fun noVerifiedAnswerIsNone() {
        assertNull(callOutcome(CallResult.unavailable("not synced")))
    }
}
