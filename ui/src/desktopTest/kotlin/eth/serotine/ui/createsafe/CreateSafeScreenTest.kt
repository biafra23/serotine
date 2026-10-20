package eth.serotine.ui.createsafe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountChoice
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [CreateSafeScreen] headless for each of its three steps. */
class CreateSafeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val daily = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val savings = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val friend = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
    private val safe = "0x2Ca0226086a7C24808217F7F8707827608B17Dc5"

    private val owners = CreateSafeUiState.Owners(
        network = "Sepolia",
        name = "",
        accounts = listOf(
            OwnerChoice(daily, "Daily", "Key on this device", selected = true),
            OwnerChoice(savings, "Savings", "Watch-only: its key is elsewhere", selected = false),
        ),
        typed = listOf(OwnerChoice(friend, null, "Typed", selected = true)),
        input = "",
        summary = "2 owners",
        deviceKeyNote = "This device's hardware key as an owner comes later.",
        canContinue = true,
    )
    private val threshold = CreateSafeUiState.Threshold(
        network = "Sepolia",
        owners = listOf(OwnerItem(daily, "Daily", "Key on this device"), OwnerItem(friend, null, "Typed")),
        threshold = 2,
        ownerCount = 2,
        shape = "2 of 2 owners sign each transaction",
        explanation = null,
        rules = listOf("Each transaction of the Safe needs this many of its owners' signatures."),
        canDecrease = true,
        canIncrease = false,
        refusal = "Not 2 of 2: with one owner lost, the others could never reach the threshold again.",
    )
    private val review = CreateSafeUiState.Review(
        network = "Sepolia",
        name = "Family vault",
        address = safe,
        signers = "1/2",
        version = "Safe 1.4.1",
        prediction = "Computed here (CREATE2). The factory's verified run at block 9 returns this address.",
        owners = threshold.owners,
        shape = "1 of 2 owners sign each transaction",
        payers = listOf(AccountChoice("Daily", daily), AccountChoice("Spare", savings)),
        payer = daily,
        fee = FeeLevel.NORMAL,
        unlock = UnlockStep(),
        quote = DeploymentQuoteItem(
            maxFee = "0.006534 ETH",
            feeDetail = "Normal fee: 297,000 gas × at most 22 gwei",
            balance = BalanceChange("ETH balance of Daily", "1.5 ETH", "at least 1.493466 ETH", "verified at block 9"),
            simulation = "Run as a verified call from this account at block 9, the factory deploys the Safe.",
        ),
    )
    private val events = mutableListOf<String>()

    private fun show(state: CreateSafeUiState) = rule.setContent {
        SerotineTheme {
            CreateSafeScreen(
                state = state,
                lightClient = verifiedStripSample,
                actions = CreateSafeActions(
                    onNameChange = { events += "name $it" },
                    onToggleOwner = { events += "toggle $it" },
                    onInputChange = { events += "input $it" },
                    onAddOwner = { events += "add" },
                    onRemoveTyped = { events += "remove $it" },
                    onThresholdChange = { events += "threshold $it" },
                    onContinue = { events += "continue" },
                    onStepBack = { events += "step back" },
                    onPayerChange = { events += "payer $it" },
                    onFeeChange = { events += "fee $it" },
                    onRefresh = { events += "refresh" },
                    onPasswordChange = { events += "password" },
                    onDeploy = { events += "deploy" },
                    onDeployWithBiometrics = { events += "biometrics" },
                    onRetry = { events += "retry" },
                ),
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun theOwnersStepListsTheAccountsAndTheTypedOwnersWithTheirChoice() {
        show(owners)

        rule.onNodeWithText("Create Safe 1/3").assertIsDisplayed()
        assertEquals(3, rule.onAllNodesWithTag(CREATE_SAFE_OWNER_TAG).fetchSemanticsNodes().size)
        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onAllNodesWithTag(CREATE_SAFE_OWNER_TAG)[0].assertIsOn()
        rule.onAllNodesWithTag(CREATE_SAFE_OWNER_TAG)[1].assertIsOff()
        rule.onNodeWithText(friend).assertIsDisplayed()
        rule.onNodeWithText("This device (hardware key)").performScrollTo().assertIsDisplayed()

        rule.onAllNodesWithTag(CREATE_SAFE_OWNER_TAG)[1].performClick()
        rule.onNodeWithContentDescription("Remove 0xdbF0…C6FB").performClick()
        rule.onNodeWithTag(CREATE_SAFE_NAME_TAG).performTextInput("Vault")
        rule.onNodeWithTag(CREATE_SAFE_INPUT_TAG).performScrollTo().performTextInput("friend.eth")
        rule.onNodeWithTag(CREATE_SAFE_ADD_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().performClick()
        // Back from the first step leaves Create Safe.
        rule.onNodeWithContentDescription("Back").performClick()

        // A stateless field keeps the state's text, and says so once more after each input: not an intent.
        assertEquals(
            listOf("toggle $savings", "remove $friend", "name Vault", "input friend.eth", "continue", "back"),
            events.filterNot { it == "name " || it == "input " },
        )
    }

    @Test
    fun aRefusedShapeIsSaidAndCannotGoOn() {
        show(threshold)

        rule.onNodeWithText("Create Safe 2/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_THRESHOLD_TAG).assertTextEquals("2 of 2")
        rule.onNodeWithText(threshold.refusal!!).assertIsDisplayed()
        rule.onNodeWithContentDescription("Raise the threshold").assertIsNotEnabled()
        rule.onNodeWithTag(CREATE_SAFE_CONTINUE_TAG).performScrollTo().assertIsNotEnabled()

        rule.onNodeWithContentDescription("Lower the threshold").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("threshold 1", "step back"), events)
    }

    @Test
    fun theReviewShowsThePredictedAddressInFullAndDeploysWithThePassword() {
        show(review.copy(unlock = UnlockStep(password = WalletPassword("correct horse battery"))))

        rule.onNodeWithText("Create Safe 3/3").assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_ADDRESS_TAG).assertTextEquals(safe)
        rule.onNodeWithText("Safe 1.4.1").assertIsDisplayed()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.onNodeWithText("At most 0.006534 ETH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1.5 ETH → at least 1.493466 ETH").performScrollTo().assertIsDisplayed()

        rule.onNodeWithText("Spare").performScrollTo().performClick()
        rule.onNodeWithText("Fast").performScrollTo().performClick()
        rule.onNodeWithTag(CREATE_SAFE_DEPLOY_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("payer $savings", "fee FAST", "deploy", "step back"), events)
    }

    @Test
    fun withoutAQuoteNothingCanBeSigned() {
        show(
            review.copy(
                quote = null,
                error = "Not enough ETH in this account for the most the fee can be.",
                unlock = UnlockStep(password = WalletPassword("correct horse battery")),
            ),
        )

        rule.onNodeWithText("Not enough ETH in this account for the most the fee can be.").performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithTag(CREATE_SAFE_DEPLOY_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aDeploymentSignedButNotSentOffersOnlyTryAgainAndBackLeaves() {
        show(review.copy(notSent = NotSentItem("Signed, but not sent: no peer took it right now.", canRetry = true)))

        rule.onNodeWithTag(CREATE_SAFE_PASSWORD_TAG).assertDoesNotExist()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("retry", "back"), events)
    }
}
