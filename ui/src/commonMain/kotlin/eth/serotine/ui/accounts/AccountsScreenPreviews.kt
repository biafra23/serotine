package eth.serotine.ui.accounts

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UpgradeBannerItem
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Accounts screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. AccountsScreenPreviewsTest renders each one in accountsScreenPreviews, at the end.
// The addresses are EIP-55's own examples, and the amounts are made up.

private val network = Network.SEPOLIA.displayName

private val synced = LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false, VERIFIED)

private val starting = LightClientItem("Starting the light client…", null, true, NOT_VERIFIED)

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

private val withAccounts = AccountsUiState.Ready(
    network,
    synced,
    accounts,
    total = "$3,125.00",
    totalNote = "Testnet prices · 1 not verified",
)

@Composable
private fun AccountsPreview(state: AccountsUiState, darkTheme: Boolean = false, fabExpanded: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        AccountsScreen(
            state = state,
            onWatchAddress = {},
            onImportPhrase = {},
            onCreateAccount = {},
            onCreateSafe = {},
            onAccountAction = {},
            fabExpandedAtStart = fabExpanded,
        )
    }
}

/** No account whose key is held here: Create Safe greyed out in the open + menu (owner ruling #55). */
private val noKeyHere = AccountsUiState.Ready(
    network,
    LightClientItem("Synced · verified head 9,412,118", null, false, VERIFIED),
    emptyList(),
)

@Preview
@Composable
private fun AccountsCreateSafeGreyedOutPreview() = AccountsPreview(noKeyHere, fabExpanded = true)

@Preview
@Composable
private fun AccountsCreateSafeReasonPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice(
            "Create Safe",
            CREATE_SAFE_NEEDS_A_KEY,
            listOf(UnavailableFix("Import recovery phrase") {}, UnavailableFix("Create account") {}),
        ),
        onDismiss = {},
    )
}

/** The dialog a tap on a greyed-out Send opens, for a watched account (owner ruling, 2026-10-18, #55). */
@Preview
@Composable
private fun AccountsSendReasonPreview() = SerotineTheme {
    UnavailableDialog(
        UnavailableNotice("Send", sendBlockedReason(AccountKind.EOA, Control.WATCH_ONLY).orEmpty()),
        onDismiss = {},
    )
}

@Preview
@Composable
private fun AccountsAccountsPreview() = AccountsPreview(withAccounts)

@Preview
@Composable
private fun AccountsAccountsDarkPreview() = AccountsPreview(withAccounts, darkTheme = true)

/** No verified head yet and no balance proven before: the strip red, every row syncing. */
private val catchingUp = AccountsUiState.Ready(
    network,
    LightClientItem("Verifying sync committees · 1 of 4 periods", 0.25f, true, NOT_VERIFIED),
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
)

@Preview
@Composable
private fun AccountsCatchingUpPreview() = AccountsPreview(catchingUp)

@Preview
@Composable
private fun AccountsCatchingUpDarkPreview() = AccountsPreview(catchingUp, darkTheme = true)

/** The verified head too old to read at: the amounts last verified, each said to be so. */
private val headTooOld = AccountsUiState.Ready(
    network,
    LightClientItem("Synced · the verified head is 4 min old, waiting for a fresh one", null, true, STALE),
    accounts.map {
        it.copy(
            balanceVerified = false,
            balanceNote = if (it.balanceVerified) {
                "Last verified Oct 19, 2026, 2:32 PM · may have changed since"
            } else {
                null
            },
            value = null,
        )
    },
    total = "Checking…",
    totalNote = "Testnet prices · still checking",
)

@Preview
@Composable
private fun AccountsHeadTooOldPreview() = AccountsPreview(headTooOld)

@Preview
@Composable
private fun AccountsHeadTooOldDarkPreview() = AccountsPreview(headTooOld, darkTheme = true)

@Preview
@Composable
private fun AccountsNoAccountsPreview() = AccountsPreview(
    AccountsUiState.Ready(
        network,
        LightClientItem(
            "Bootstrapping from the trusted checkpoint · looking for light-client servers",
            null,
            true,
            NOT_VERIFIED,
        ),
        emptyList(),
    ),
)

@Preview
@Composable
private fun AccountsLoadingPreview() =
    AccountsPreview(AccountsUiState.Loading(network, starting))

@Preview
@Composable
private fun AccountsLoadingCreateSafeGreyedOutPreview() = AccountsPreview(
    AccountsUiState.Loading(network, starting),
    fabExpanded = true,
)

@Preview
@Composable
private fun AccountsListSetAsidePreview() = AccountsPreview(
    AccountsUiState.Ready(
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
private fun AccountsUpgradeAheadPreview() = AccountsPreview(
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
private fun AccountsUpgradeActivatedDarkPreview() = AccountsPreview(
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
private fun AccountsListUnavailablePreview() = AccountsPreview(
    AccountsUiState.ListUnavailable(
        network,
        synced,
        "Your saved account list cannot be read right now. Nothing has been changed, and no account can be " +
            "added until it can be read. Serotine keeps trying.",
    ),
)

/** Every preview above, which AccountsScreenPreviewsTest renders: a new preview goes in here too. */
internal val accountsScreenPreviews: List<@Composable () -> Unit> = listOf(
    { AccountsAccountsPreview() },
    { AccountsAccountsDarkPreview() },
    { AccountsCatchingUpPreview() },
    { AccountsHeadTooOldPreview() },
    { AccountsHeadTooOldDarkPreview() },
    { AccountsNoAccountsPreview() },
    { AccountsCreateSafeGreyedOutPreview() },
    { AccountsCreateSafeReasonPreview() },
    { AccountsSendReasonPreview() },
    { AccountsLoadingPreview() },
    { AccountsLoadingCreateSafeGreyedOutPreview() },
    { AccountsListSetAsidePreview() },
    { AccountsUpgradeAheadPreview() },
    { AccountsUpgradeActivatedDarkPreview() },
    { AccountsListUnavailablePreview() },
    { AccountsCatchingUpDarkPreview() },
)
