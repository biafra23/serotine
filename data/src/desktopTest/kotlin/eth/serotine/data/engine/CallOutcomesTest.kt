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
