package eth.serotine.data.engine

import io.myotis.api.EngineException
import io.myotis.api.EnsResolutionResult
import io.myotis.api.EstimateResult
import io.myotis.api.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionAnswersTest {

    @Test
    fun everyEstimateStatusIsToldApart() {
        assertEquals(EstimateAnswer.Gas(21_000), estimateAnswer(EstimateResult.ok(21_000)))
        val reverted = EstimateResult.reverted(byteArrayOf(8, -61, 121, -96))
        assertEquals(EstimateAnswer.Reverted("08c379a0"), estimateAnswer(reverted))
        assertEquals(EstimateAnswer.Reverted(""), estimateAnswer(EstimateResult.reverted(null)))
        assertEquals(
            EstimateAnswer.Infeasible("insufficient funds"),
            estimateAnswer(EstimateResult.infeasible("insufficient funds")),
        )
        assertEquals(EstimateAnswer.Refused("fork"), estimateAnswer(EstimateResult.refused("fork")))
        assertEquals(EstimateAnswer.Unavailable, estimateAnswer(EstimateResult.unavailable(null)))
    }

    @Test
    fun everySendStatusIsToldApart() {
        val hash = ByteArray(32) { 0xab.toByte() }
        assertEquals(SendAnswer.Sent("0x" + "ab".repeat(32)), sendAnswer(SendResult.sent(hash)))
        assertEquals(SendAnswer.Rejected("nonce too low"), sendAnswer(SendResult.rejected("nonce too low")))
        assertEquals(SendAnswer.Refused("malformed"), sendAnswer(SendResult.refused("malformed")))
        assertEquals(SendAnswer.Unavailable(null), sendAnswer(SendResult.unavailable(null)))
    }

    @Test
    fun anOffchainNameIsToldApartFromAFailure() {
        assertEquals(
            NameAnswer("0x" + "bb".repeat(20), 90, verified = true, error = null),
            nameAnswerOf { EnsResolutionResult("bob.eth", "0x" + "bb".repeat(20), 90, true, null) },
        )
        val offchain = nameAnswerOf {
            throw EngineException("name resolves offchain (ERC-3668) and no CCIP-Read HTTP gateway is configured")
        }
        assertEquals(true, offchain.offchain)
        assertEquals(true, nameAnswer(EnsResolutionResult("x.eth", null, -1, false, "offchain lookup")).offchain)
        // Anything else the engine throws is not taken for an off-chain name.
        assertFailsWith<EngineException> { nameAnswerOf { throw EngineException("no snap peer") } }
    }
}
