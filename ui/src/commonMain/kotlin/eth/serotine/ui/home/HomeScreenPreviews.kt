package eth.serotine.ui.home

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Home screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. HomeScreenPreviewsTest renders each one in homeScreenPreviews, at the end.

private val network = Network.SEPOLIA.displayName

private val synced = LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false)

private val accounts = listOf(
    AccountItem("Daily", "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", AccountKind.EOA, "1.25 ETH", true),
    AccountItem("Savings", "0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359", AccountKind.EOA, "< 0.0001 ETH", true),
    AccountItem("Treasury", "0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb", AccountKind.SAFE, "Not verified", false),
)

private val withAccounts = HomeUiState.Ready(network, synced, accounts)

@Composable
private fun HomePreview(state: HomeUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        HomeScreen(state = state, onAddAccount = {})
    }
}

@Preview
@Composable
private fun HomeAccountsPreview() = HomePreview(withAccounts)

@Preview
@Composable
private fun HomeAccountsDarkPreview() = HomePreview(withAccounts, darkTheme = true)

@Preview
@Composable
private fun HomeCatchingUpPreview() = HomePreview(
    HomeUiState.Ready(
        network,
        LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true),
        accounts.map { it.copy(balance = "Syncing…", balanceVerified = false) },
    ),
)

@Preview
@Composable
private fun HomeNoAccountsPreview() = HomePreview(
    HomeUiState.Ready(
        network,
        LightClientItem("Bootstrapping from the trusted checkpoint · looking for light-client servers", null, true),
        emptyList(),
    ),
)

@Preview
@Composable
private fun HomeLoadingPreview() =
    HomePreview(HomeUiState.Loading(network, LightClientItem("Starting the light client…", null, true)))

@Preview
@Composable
private fun HomeListSetAsidePreview() = HomePreview(
    HomeUiState.Ready(
        network,
        synced,
        listOf(accounts.first().copy(balance = "Checking…", balanceVerified = false)),
        notice = "Your saved account list was damaged and has been set aside. Add your accounts again.",
    ),
)

@Preview
@Composable
private fun HomeListUnavailablePreview() = HomePreview(
    HomeUiState.ListUnavailable(
        network,
        synced,
        "Your saved account list cannot be read right now. Nothing has been changed, and no account can be " +
            "added until it can be read. Serotine keeps trying.",
    ),
)

/** Every preview above, which HomeScreenPreviewsTest renders: a new preview goes in here too. */
internal val homeScreenPreviews: List<@Composable () -> Unit> = listOf(
    { HomeAccountsPreview() },
    { HomeAccountsDarkPreview() },
    { HomeCatchingUpPreview() },
    { HomeNoAccountsPreview() },
    { HomeLoadingPreview() },
    { HomeListSetAsidePreview() },
    { HomeListUnavailablePreview() },
)
