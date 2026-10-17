package eth.serotine.ui.tokens

import androidx.compose.runtime.Composable
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Tokens screen (Settings → Tokens) in Android Studio's preview pane, one preview per state
// it draws differently. Built from display-ready values, as the screen tests are, never from
// the ViewModel. TokensScreenPreviewsTest renders each one in tokensScreenPreviews, at the end.
// The contract addresses are EIP-55's own examples, not the tokens' own.

private const val CHECKED = "Built in · checked against its contract at block 8,412,307"

private val usdc = TokenItem(
    "USDC",
    "USDC",
    "0x52908400098527886E0F7030069857D2E4169EE7",
    "usdc",
    builtIn = true,
    status = CHECKED,
    problem = false,
    hidden = false,
)

private val link = TokenItem(
    "LINK",
    "ChainLink Token",
    "0x8617E340B3D01FA5F11F306F4090FD50E238070D",
    "link",
    builtIn = true,
    status = CHECKED,
    problem = false,
    hidden = false,
)

private val uni = TokenItem(
    "UNI",
    "Uniswap",
    "0xde709f2102306220921060314715629080e2fb77",
    "uni",
    builtIn = true,
    status = CHECKED,
    problem = false,
    hidden = true,
)

private val eurc = TokenItem(
    "EURC",
    "Euro Coin",
    "0x27b1fdb04752bbc536007a920d24acb045561c26",
    null,
    builtIn = false,
    status = "Added: as its contract describes itself",
    problem = false,
    hidden = false,
)

private val known = TokensUiState(listOf(usdc, link, uni, eurc))

@Composable
private fun TokensPreview(state: TokensUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        TokensScreen(state = state, onBack = {}, onAddToken = {}, onCopy = {}, onSetHidden = { _, _ -> }, onRemove = {})
    }
}

@Preview
@Composable
private fun TokensListPreview() = TokensPreview(known)

@Preview
@Composable
private fun TokensListDarkPreview() = TokensPreview(known, darkTheme = true)

@Preview
@Composable
private fun TokensChecksPreview() = TokensPreview(
    TokensUiState(
        listOf(
            usdc,
            link.copy(status = "Built in · checking its contract…"),
            uni.copy(
                status = "Not shown: its contract does not answer as a token",
                problem = true,
                hidden = false,
            ),
            TokenItem(
                "USDC",
                "USD Coin",
                "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
                null,
                builtIn = false,
                status = "Added: as its contract describes itself",
                problem = false,
                hidden = false,
                warning = "Not the built-in USDC: another contract",
            ),
        ),
    ),
)

@Preview
@Composable
private fun TokensUnreadablePreview() = TokensPreview(
    TokensUiState(
        listOf(usdc, link, uni.copy(hidden = false)),
        notice = "Your token settings cannot be read right now, so nothing can be added, hidden or removed. " +
            "Serotine keeps trying.",
        canChange = false,
    ),
)

/** Every preview above, which TokensScreenPreviewsTest renders: a new preview goes in here too. */
internal val tokensScreenPreviews: List<@Composable () -> Unit> = listOf(
    { TokensListPreview() },
    { TokensListDarkPreview() },
    { TokensChecksPreview() },
    { TokensUnreadablePreview() },
)
