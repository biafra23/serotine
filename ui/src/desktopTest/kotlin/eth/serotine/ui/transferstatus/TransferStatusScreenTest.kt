package eth.serotine.ui.transferstatus

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [TransferStatusScreen] headless. */
class TransferStatusScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val hash = "0x" + "5e".repeat(32)
    private val events = mutableListOf<String>()

    private fun show(state: TransferStatusUiState) = rule.setContent {
        SerotineTheme {
            TransferStatusScreen(state, onDone = { events += "done" })
        }
    }

    @Test
    fun whatWasSentAndEachStepWithItsBlock() {
        show(
            TransferStatusUiState.Shown(
                network = "Sepolia",
                hash = hash,
                sent = SentSummary(
                    "0.1 ETH",
                    "ETH",
                    "eth",
                    "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
                    "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
                ),
                steps = listOf(
                    StatusStep("Signed", StepState.DONE, null),
                    StatusStep("Broadcast", StepState.DONE, null),
                    StatusStep("Included", StepState.DONE, "In block 9,412,120, its receipt verified"),
                    StatusStep("Finalized", StepState.WAITING, "Once the beacon chain finalizes its block"),
                ),
            ),
        )

        rule.onNodeWithText("0.1 ETH").assertIsDisplayed()
        rule.onNodeWithText("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359").assertIsDisplayed()
        rule.onAllNodesWithTag(TRANSFER_STEP_TAG).assertCountEquals(4)
        rule.onNodeWithText("In block 9,412,120, its receipt verified").assertIsDisplayed()
        rule.onNodeWithTag(TRANSFER_HASH_TAG).performScrollTo().assertTextEquals(hash)
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("done", "done"), events)
    }

    @Test
    fun aTransferWithoutARecordAndAReceiptThatCannotBeVerifiedSaySo() {
        show(
            TransferStatusUiState.Shown(
                network = "Sepolia",
                hash = hash,
                sent = null,
                steps = listOf(
                    StatusStep("Signed", StepState.DONE, null),
                    StatusStep("Broadcast", StepState.DONE, null),
                    StatusStep("Included", StepState.UNKNOWN, "Cannot be verified right now"),
                    StatusStep("Finalized", StepState.UNKNOWN, "Cannot be verified right now"),
                ),
                note = "Where it stands cannot be verified right now: the light client refuses to sync.",
            ),
        )

        rule.onNodeWithText("This device has no record of what was sent", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Where it stands cannot be verified right now: the light client refuses to sync.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
