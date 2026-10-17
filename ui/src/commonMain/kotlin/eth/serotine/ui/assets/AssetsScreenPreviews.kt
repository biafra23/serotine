package eth.serotine.ui.assets

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UpgradeBannerItem
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Assets screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AssetsScreenPreviewsTest renders each one in assetsScreenPreviews, at the end.
// The addresses are EIP-55's own examples, and the amounts are made up.

private val network = Network.SEPOLIA.displayName

private val synced = LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false)

private val accounts = listOf(
    AccountItem(
        "Daily",
        "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        AccountKind.EOA,
        Control.FULL_CONTROL,
        "1.25 ETH",
        balanceVerified = true,
        value = "$3,125.00 · testnet prices",
    ),
    AccountItem(
        "Savings",
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        AccountKind.EOA,
        Control.WATCH_ONLY,
        "< 0.0001 ETH",
        balanceVerified = true,
        value = "< $0.01 · testnet prices",
    ),
    AccountItem(
        "Treasury",
        "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
        AccountKind.SAFE,
        Control.CO_SIGNER,
        "Not verified",
        balanceVerified = false,
        signers = "2/3",
        kindNote = "Safe",
    ),
)

private val withAccounts = AssetsUiState.Ready(
    network,
    synced,
    accounts,
    total = "$3,125.00",
    totalNote = "Testnet prices · 1 not verified",
)

@Composable
private fun AssetsPreview(state: AssetsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        AssetsScreen(
            state = state,
            onWatchAddress = {},
            onImportPhrase = {},
            onCreateAccount = {},
            onAccountAction = {},
        )
    }
}

@Preview
@Composable
private fun AssetsAccountsPreview() = AssetsPreview(withAccounts)

@Preview
@Composable
private fun AssetsAccountsDarkPreview() = AssetsPreview(withAccounts, darkTheme = true)

@Preview
@Composable
private fun AssetsCatchingUpPreview() = AssetsPreview(
    AssetsUiState.Ready(
        network,
        LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true),
        // Until a Safe is read it is never shown as more than watch-only (AccountItem.from).
        accounts.map {
            it.copy(
                balance = "Syncing…",
                balanceVerified = false,
                value = null,
                signers = null,
                control = if (it.kind == AccountKind.SAFE) Control.WATCH_ONLY else it.control,
            )
        },
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
    ),
)

@Preview
@Composable
private fun AssetsNoAccountsPreview() = AssetsPreview(
    AssetsUiState.Ready(
        network,
        LightClientItem("Bootstrapping from the trusted checkpoint · looking for light-client servers", null, true),
        emptyList(),
    ),
)

@Preview
@Composable
private fun AssetsLoadingPreview() =
    AssetsPreview(AssetsUiState.Loading(network, LightClientItem("Starting the light client…", null, true)))

@Preview
@Composable
private fun AssetsListSetAsidePreview() = AssetsPreview(
    AssetsUiState.Ready(
        network,
        synced,
        listOf(accounts.first().copy(balance = "Checking…", balanceVerified = false, value = null)),
        notice = "Your saved account list was damaged and has been set aside. Add your accounts again.",
        total = "Checking…",
        totalNote = "Testnet prices · still checking",
    ),
)

private const val UPGRADE_DETAILS = "Reported by peers in 5 networks, not verified · fork id 0x6c1d9423"

@Preview
@Composable
private fun AssetsUpgradeAheadPreview() = AssetsPreview(
    withAccounts.copy(
        upgrade = UpgradeBannerItem(
            "Network upgrade ahead: update Serotine",
            "Peers announce a Sepolia network upgrade on Nov 6, 2026, 3:00 PM that this version of Serotine " +
                "does not support. Update the app before it activates, or Serotine stops verifying at the upgrade.",
            UPGRADE_DETAILS,
            urgent = false,
        ),
    ),
)

@Preview
@Composable
private fun AssetsUpgradeActivatedDarkPreview() = AssetsPreview(
    withAccounts.copy(
        upgrade = UpgradeBannerItem(
            "Network upgrade reported: update Serotine",
            "Peers report that Sepolia upgraded on Nov 6, 2026, 3:00 PM to rules this version of Serotine does " +
                "not support. Update the app to keep using your wallet.",
            UPGRADE_DETAILS,
            urgent = false,
        ),
    ),
    darkTheme = true,
)

@Preview
@Composable
private fun AssetsListUnavailablePreview() = AssetsPreview(
    AssetsUiState.ListUnavailable(
        network,
        synced,
        "Your saved account list cannot be read right now. Nothing has been changed, and no account can be " +
            "added until it can be read. Serotine keeps trying.",
    ),
)

/** Every preview above, which AssetsScreenPreviewsTest renders: a new preview goes in here too. */
internal val assetsScreenPreviews: List<@Composable () -> Unit> = listOf(
    { AssetsAccountsPreview() },
    { AssetsAccountsDarkPreview() },
    { AssetsCatchingUpPreview() },
    { AssetsNoAccountsPreview() },
    { AssetsLoadingPreview() },
    { AssetsListSetAsidePreview() },
    { AssetsUpgradeAheadPreview() },
    { AssetsUpgradeActivatedDarkPreview() },
    { AssetsListUnavailablePreview() },
)
