package eth.serotine.ui.safepropose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.ActionAddress
import eth.serotine.ui.components.AssetChoice
import eth.serotine.ui.components.RecipientItem
import eth.serotine.ui.components.SAFE_TX_ACTION_TAG
import eth.serotine.ui.components.SEND_AMOUNT_TAG
import eth.serotine.ui.components.SEND_REVIEW_TAG
import eth.serotine.ui.components.SafeBalanceChange
import eth.serotine.ui.components.SafeTxActionItem
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.TransferForm
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [SafeProposeScreen] headless for its form and its review. */
class SafeProposeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val treasury = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val ether = AssetChoice("ETH", "Ether", "eth", "2.5 ETH", verifiedAt = "block 9")
    private val form = TransferForm(
        network = "Sepolia",
        from = SendFrom("Treasury", treasury),
        assets = listOf(ether),
        asset = ether,
        to = savings,
        accounts = listOf(AccountChoice("Daily", daily)),
        amount = "",
        recipient = RecipientItem(savings),
        canMax = true,
    )
    private val review = SafeProposeUiState.Review(
        network = "Sepolia",
        from = form.from,
        action = SafeTxActionItem("Send 0.5 ETH", listOf(ActionAddress("To", savings)), symbol = "ETH"),
        amount = "0.5 ETH",
        asset = ether,
        recipient = RecipientItem(savings),
        nonce = "Safe nonce 13",
        balance = SafeBalanceChange(
            "ETH balance of the Safe",
            "2.5 ETH",
            "1.5 ETH",
            "verified at block 9",
            queued = "0.5 ETH moved by those queued before it",
        ),
        simulation = "Not simulated: the light client on this device does not run a call from the Safe's address.",
        owners = listOf(AccountChoice("Daily", daily)),
        owner = daily,
        unlock = UnlockStep(WalletPassword("made-up test password")),
        queued = "1 transaction queued on this device runs before it.",
    )
    private val events = mutableListOf<String>()

    private fun show(state: SafeProposeUiState) = rule.setContent {
        SerotineTheme {
            SafeProposeScreen(
                state = state,
                lightClient = verifiedStripSample,
                actions = SafeProposeActions(
                    onAmountChange = { events += "amount $it" },
                    onMax = { events += "max" },
                    onReview = { events += "review" },
                    onEdit = { events += "edit" },
                    onRefresh = { events += "refresh" },
                    onOwnerChange = { events += "owner $it" },
                    onPasswordChange = { events += "password" },
                    onSign = { events += "sign" },
                    onSignWithBiometrics = { events += "biometrics" },
                    onDropUnsaved = { events += "drop" },
                    onKeepUnsaved = { events += "keep" },
                ),
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theFormIsSendsFromTheSafeWithNoFeeToChoose() {
        show(SafeProposeUiState.Compose(form))

        rule.onNodeWithText("Send from a Safe").assertIsDisplayed()
        rule.onNodeWithText("Treasury").assertIsDisplayed()
        rule.onNodeWithText("Fee").assertDoesNotExist()
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performTextInput("0.5")
        rule.onNodeWithText("Max").performClick()
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("amount 0.5", "max", "back"), events.filterNot { it == "amount " })
    }

    @Test
    fun backWithASignatureNotSavedAsksBeforeLeavingItBehind() {
        show(review.copy(askingToDrop = true))

        rule.onNodeWithText("Leave the signature behind?").assertIsDisplayed()
        rule.onNodeWithText("Stay and share").performClick()
        rule.onNodeWithTag(PROPOSE_DROP_UNSAVED_TAG).performClick()
        assertEquals(listOf("keep", "drop"), events)
    }

    @Test
    fun aRefusedProposalIsSaidOnTheForm() {
        show(SafeProposeUiState.Compose(form.copy(error = "The Safe holds less ETH than that.", canReview = true)))

        rule.onNodeWithText("The Safe holds less ETH than that.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SEND_REVIEW_TAG).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("review"), events)
    }

    @Test
    fun theReviewShowsTheSafeTransactionItsNonceTheBalanceAndWhoSigns() {
        show(review)

        rule.onNodeWithTag(PROPOSE_AMOUNT_REVIEWED_TAG).assertTextEquals("0.5 ETH")
        rule.onNodeWithTag(SAFE_TX_ACTION_TAG).performScrollTo().assertTextEquals("Send 0.5 ETH")
        rule.onNodeWithText("Safe nonce 13").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 transaction queued on this device runs before it.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("− 0.5 ETH moved by those queued before it").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("→ 1.5 ETH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "Not simulated: the light client on this device does not run a call from the Safe's address.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Propose it again").performScrollTo().performClick()
        rule.onNodeWithTag(PROPOSE_SIGN_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("refresh", "sign", "edit"), events)
    }

    @Test
    fun whileSigningBackWaits() {
        show(review.copy(signing = true))

        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithTag(PROPOSE_SIGN_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun whileTheSafeIsCheckedBeforeSigningItSaysSoAndBackStopsIt() {
        show(review.copy(signing = true, checking = true))

        rule.onNodeWithText("Checking the Safe on chain…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        assertEquals(listOf("edit"), events)
    }

    @Test
    fun aSafeThatCannotProposeSaysWhy() {
        show(SafeProposeUiState.CannotPropose("Sepolia", "No owner of this Safe has its key on this device."))

        rule.onNodeWithText("No owner of this Safe has its key on this device.").assertIsDisplayed()
    }

    @Test
    fun theKeyboardsDoneOnTheAmountReviews() {
        show(SafeProposeUiState.Compose(form.copy(canReview = true)))
        rule.onNodeWithTag(SEND_AMOUNT_TAG).performScrollTo().performImeAction()
        assertEquals(listOf("review"), events)
    }
}
