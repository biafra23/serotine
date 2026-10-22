package eth.serotine.ui.coworder

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.domain.model.WalletPassword
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.SendFrom
import eth.serotine.ui.components.SpenderItem
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless [CowOrderScreen] headless for its states. */
class CowOrderScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val from = SendFrom("Daily", "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2")
    private val settlement = SpenderItem("CoW's settlement contract", "0x9008D19f58AAbD9eD0D60971565AA8510560ab41")
    private val uid = "0x" + "c0".repeat(56)

    private val live = CowOrderUiState.Shown(
        network = "Sepolia",
        from = from,
        sold = "40 USDC",
        soldSymbol = "USDC",
        soldIcon = "usdc",
        bought = "at least 0.0159 ETH",
        boughtSymbol = "ETH",
        boughtIcon = "eth",
        state = OrderState.LIVE,
        end = "Not proven yet: it may fill until 14:27.",
        cow = "CoW says, not verified: the order is open in its order book, in the auction.",
        placement = "CoW took the order when it was sent.",
        validUntil = "Valid until 14:27.",
        settlement = settlement,
        uid = uid,
        canCancel = true,
    )

    private val events = mutableListOf<String>()

    private fun show(state: CowOrderUiState) = rule.setContent {
        SerotineTheme {
            CowOrderScreen(
                state = state,
                lightClient = verifiedStripSample,
                onCancel = { events += "cancel" },
                onEdit = { events += "edit" },
                onRefresh = { events += "refresh" },
                onPasswordChange = { events += "password" },
                onSign = { events += "sign" },
                onSignWithBiometrics = { events += "biometrics" },
                onRetry = { events += "retry" },
                onOpenTransfer = { events += "open $it" },
                onSwapAgain = { events += "again ${it.amount}" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun aLiveOrderSaysCowsWordAsCowsAndCancelsOnChain() {
        show(live)

        rule.onNodeWithText("Live").assertExists()
        rule.onNodeWithText("CoW says, not verified: the order is open in its order book, in the auction.")
            .performScrollTo()
        rule.onNodeWithTag(COW_ORDER_CANCEL_TAG).performScrollTo().performClick()

        assertEquals(listOf("cancel"), events)
    }

    @Test
    fun anOrderProvenNotToHaveFilledOffersTheSameSwapOnUniswap() {
        show(
            live.copy(
                state = OrderState.NOT_FILLED,
                end = "Not filled.",
                cow = null,
                canCancel = false,
                uniswapAgain = SwapAgain("0x" + "8f".repeat(20), "0x" + "1c".repeat(20), buy = null, amount = "40"),
            ),
        )

        rule.onNodeWithText("Not filled").assertExists()
        rule.onNodeWithTag(COW_ORDER_UNISWAP_TAG).performScrollTo().performClick()

        assertEquals(listOf("again 40"), events)
    }

    @Test
    fun theCancellationsReviewSignsWithThePasswordAndGoesBackToTheOrder() {
        show(
            CowOrderUiState.CancelReview(
                network = "Sepolia",
                from = from,
                uid = uid,
                settlement = settlement,
                maxFee = "0.000957 ETH",
                feeDetail = "Normal fee",
                balance = BalanceChange("ETH balance, which pays the fee", "1.25 ETH", "at least 1.249 ETH", "block 1"),
                simulation = "The cancellation goes through.",
                unlock = UnlockStep(WalletPassword("made-up test password")),
            ),
        )

        rule.onNodeWithText("Cancel on chain").assertExists()
        rule.onNodeWithTag(COW_ORDER_SIGN_TAG).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("sign", "edit"), events)
    }
}
