package eth.serotine.ui.activitydetail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless Activity detail headless. */
class ActivityDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val copied = mutableListOf<String>()
    private val statuses = mutableListOf<String>()
    private val added = mutableListOf<String>()
    private val bob = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val hash = "0x" + "1".repeat(64)
    private val sent = ActivityDetailUiState.Shown(
        title = "Alice",
        headline = "Sent 0.25 ETH",
        about = bob,
        status = "In block 9,412,118",
        state = ActivityState.DONE,
        fields = listOf(
            DetailField("To", bob, DetailKind.ADDRESS, "Bob"),
            DetailField("Amount", "0.25 ETH"),
            DetailField("Transaction hash", hash, DetailKind.HASH),
        ),
        opensStatus = hash,
    )

    private fun show(state: ActivityDetailUiState) = rule.setContent {
        SerotineTheme {
            ActivityDetailScreen(
                state,
                verifiedStripSample,
                onBack = {},
                onCopy = { copied += it },
                onStatus = { statuses += it },
                onAddToken = { added += it },
            )
        }
    }

    @Test
    fun everyAddressAndHashIsShownInFullAndCopiedSo() {
        show(sent)

        rule.onNodeWithText("Sent 0.25 ETH").assertIsDisplayed()
        rule.onNodeWithText("In block 9,412,118").assertIsDisplayed()
        rule.onNodeWithText(bob).assertIsDisplayed()
        rule.onNodeWithText("Bob").assertIsDisplayed()
        rule.onAllNodesWithTag(ACTIVITY_DETAIL_FIELD_TAG).assertCountEquals(3)
        rule.onNodeWithContentDescription("Copy to").performClick()
        rule.onNodeWithContentDescription("Copy transaction hash").performScrollTo().performClick()

        assertEquals(listOf(bob, hash), copied)
    }

    @Test
    fun aTransactionSentFromHereOpensItsStatus() {
        show(sent)

        rule.onNodeWithText("Status").performClick()

        assertEquals(listOf(hash), statuses)
        rule.onAllNodesWithText("Add token").assertCountEquals(0)
    }

    @Test
    fun anUnlistedContractCanBeAddedAsAToken() {
        val contract = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"
        show(
            sent.copy(
                headline = "An unlisted token logged a transfer to it",
                fields = listOf(DetailField("Token contract", contract, DetailKind.ADDRESS)),
                note = "Only the contract is named here.",
                addToken = contract,
                opensStatus = null,
            ),
        )

        rule.onNodeWithText("Only the contract is named here.").assertIsDisplayed()
        rule.onNodeWithText("Add token").performClick()

        assertEquals(listOf(contract), added)
        rule.onAllNodesWithText("Status").assertCountEquals(0)
    }

    @Test
    fun anUnavailableDetailSaysWhy() {
        show(ActivityDetailUiState.Unavailable("Alice", "This transaction is not in this account's history."))

        rule.onNodeWithText("This transaction is not in this account's history.").assertIsDisplayed()
    }
}
