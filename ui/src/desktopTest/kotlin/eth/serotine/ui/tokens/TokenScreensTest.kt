package eth.serotine.ui.tokens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eth.serotine.ui.addtoken.AddTokenScreen
import eth.serotine.ui.addtoken.AddTokenUiState
import eth.serotine.ui.addtoken.TokenCard
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Renders the stateless Tokens and Add token screens headless. */
class TokenScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private val usdc = TokenItem(
        "USDC",
        "USDC",
        "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238",
        "usdc",
        builtIn = true,
        status = "Built in · checked against its contract at block 9",
        problem = false,
        hidden = false,
    )
    private val added = TokenItem(
        "EURC",
        "EURC",
        "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4",
        null,
        builtIn = false,
        status = "Added: as its contract describes itself",
        problem = false,
        hidden = true,
    )

    @Test
    fun everyTokenHasItsStatusAndAVisibleMenu() {
        rule.setContent {
            SerotineTheme {
                TokensScreen(
                    TokensUiState(listOf(usdc, added)),
                    onBack = {},
                    onAddToken = { events += "add" },
                    onCopy = { events += "copy $it" },
                    onSetHidden = { address, hidden -> events += "hidden $address $hidden" },
                    onRemove = { events += "remove $it" },
                )
            }
        }

        rule.onNodeWithText("Built in · checked against its contract at block 9").assertIsDisplayed()
        rule.onNodeWithText("Hidden").assertIsDisplayed()
        rule.onNodeWithText("Add token").performClick()
        rule.onNodeWithContentDescription("Actions for USDC").performClick()
        rule.onNodeWithText("Remove").assertDoesNotExist() // a built-in token is only hidden
        rule.onNodeWithText("Hide token").performClick()
        rule.onNodeWithContentDescription("Actions for EURC").performClick()
        rule.onNodeWithText("Show token").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf("add", "hidden ${usdc.address} true", "remove ${added.address}"), events)
    }

    @Test
    fun addTokenShowsTheClaimAndAddsOnlyAToken() {
        rule.setContent {
            SerotineTheme {
                AddTokenScreen(
                    AddTokenUiState(
                        address = added.address,
                        card = TokenCard.Claim(
                            added.address,
                            "EURC",
                            "Euro Coin",
                            6,
                            "Read from the contract at block 9",
                            warning = null,
                        ),
                    ),
                    onAddressChange = {},
                    onAdd = { events += "add" },
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("Euro Coin · EURC").assertIsDisplayed()
        rule.onNodeWithText("6 decimals, as the contract says").assertIsDisplayed()
        rule.onNodeWithText("EU").assertIsDisplayed() // the monogram it will be drawn with
        rule.onNode(hasText("Add token") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(listOf("add"), events)
    }

    @Test
    fun aNonTokenCannotBeAdded() {
        rule.setContent {
            SerotineTheme {
                AddTokenScreen(
                    AddTokenUiState(
                        address = added.address,
                        card = TokenCard.Unavailable("This address does not answer as a token."),
                    ),
                    onAddressChange = {},
                    onAdd = {},
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("This address does not answer as a token.").assertIsDisplayed()
        rule.onNode(hasText("Add token") and hasClickAction()).assertIsNotEnabled()
    }
}
