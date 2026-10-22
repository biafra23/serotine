package eth.serotine.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.LightClientStrip
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.NameWithControlBadge
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.SpeedDialAction
import eth.serotine.ui.components.SpeedDialFab
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.platform.rememberCopyText
import eth.serotine.ui.update.UpgradeBanner
import kotlinx.coroutines.launch

/** Test tag of the account list, for UI tests. */
const val ACCOUNTS_LIST_TAG = "accounts-list"

/** Test tag of the notice when the stored list is not what is shown: set aside, or unavailable. */
const val ACCOUNTS_NOTICE_TAG = "accounts-notice"

/** Test tag of the label naming the network the app works on, for UI tests. */
const val ACCOUNTS_NETWORK_TAG = "accounts-network"

/** Test tag of the portfolio's total, for UI tests. */
const val ACCOUNTS_TOTAL_TAG = "accounts-total"

/**
 * Binds [AccountsScreen] to its ViewModel: the only composable that knows the ViewModel
 * exists. Copy is done here, on the platform's clipboard; every other action is
 * [onAccountAction]'s, which navigates.
 */
@Composable
fun AccountsRoute(
    viewModel: AccountsViewModel,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copy = rememberCopyText()
    AccountsScreen(
        state = state,
        onWatchAddress = onWatchAddress,
        onImportPhrase = onImportPhrase,
        onCreateAccount = onCreateAccount,
        onCreateSafe = onCreateSafe,
        onAccountAction = { action ->
            if (action is AccountAction.Copy) copy(action.address) else onAccountAction(action)
        },
        modifier = modifier,
    )
}

/**
 * The Accounts screen as a pure function of [state]; stateless, so a test can render any
 * state directly. Its floating action button fans out into Watch address ([onWatchAddress]),
 * Import recovery phrase ([onImportPhrase]), Create account ([onCreateAccount]) and Create Safe
 * ([onCreateSafe]), greyed out until an account's key is known to be held here to pay for its
 * deployment (while the list is read, too), a tap then saying why, with the ways to get one; a
 * row's tap and its long-press menu (a right click on desktop) ask [onAccountAction], its Send
 * greyed out with its reason where it cannot be done (owner ruling, 2026-10-18, #55).
 * [fabExpandedAtStart] opens the button's fan from the start (a preview).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onCreateAccount: () -> Unit,
    onCreateSafe: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
    fabExpandedAtStart: Boolean = false,
) {
    val snackbar = remember { SnackbarHostState() }
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    val scope = rememberCoroutineScope()
    val act: (AccountAction) -> Unit = { action ->
        onAccountAction(action)
        if (action is AccountAction.Copy) scope.launch { snackbar.showSnackbar("Address copied") }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // No add to offer while the stored list cannot be read: it would be refused.
            if (state !is AccountsUiState.ListUnavailable) {
                // An account held here pays for the deployment (docs/wallet-ux.md, screen 2): until one
                // is known to be, Create Safe is greyed out, and says so; the ways to get one only once
                // the list is read and has none.
                val createSafeReason = state.createSafeReason
                val createSafeFixes = if (state is AccountsUiState.Ready) {
                    listOf(
                        UnavailableFix("Import recovery phrase", onImportPhrase),
                        UnavailableFix("Create account", onCreateAccount),
                    )
                } else {
                    emptyList()
                }
                SpeedDialFab(
                    actions = listOf(
                        SpeedDialAction("Watch address", SerotineIcons.Visibility, onClick = onWatchAddress),
                        SpeedDialAction("Import recovery phrase", SerotineIcons.Download, onClick = onImportPhrase),
                        SpeedDialAction("Create account", Icons.Filled.Add, onClick = onCreateAccount),
                        SpeedDialAction("Create Safe", Icons.Filled.Lock, unavailable = createSafeReason != null) {
                            if (createSafeReason == null) {
                                onCreateSafe()
                            } else {
                                unavailable = UnavailableNotice("Create Safe", createSafeReason, createSafeFixes)
                            }
                        },
                    ),
                    description = "Add account",
                    expandedAtStart = fabExpandedAtStart,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                // The one network the app works on, always in sight, so a testnet wallet
                // is never mistaken for a mainnet one.
                actions = { NetworkLabel(state.network, Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LightClientStrip(state.lightClient)
            state.upgrade?.let { UpgradeBanner(it) }
            (state as? AccountsUiState.Ready)?.let { ready -> ready.total?.let { PortfolioTotal(it, ready.totalNote) } }
            (state as? AccountsUiState.Ready)?.notice?.let { notice ->
                Text(
                    notice,
                    Modifier.padding(16.dp).testTag(ACCOUNTS_NOTICE_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Rows(state, act) { unavailable = it }
            }
        }
    }
}

/** The portfolio's total in the display currency, and what it leaves out (docs/wallet-ux.md, screen 1). */
@Composable
private fun PortfolioTotal(total: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(total, Modifier.testTag(ACCOUNTS_TOTAL_TAG), style = MaterialTheme.typography.headlineMedium)
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoxScope.Rows(
    state: AccountsUiState,
    onAccountAction: (AccountAction) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
) {
    when (state) {
        is AccountsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is AccountsUiState.ListUnavailable -> Text(
            state.message,
            Modifier.align(Alignment.Center).padding(24.dp).testTag(ACCOUNTS_NOTICE_TAG),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        is AccountsUiState.Ready ->
            if (state.accounts.isEmpty()) {
                NoAccounts(Modifier.align(Alignment.Center))
            } else {
                AccountList(state.accounts, onAccountAction, onUnavailable)
            }
    }
}

/** The network's name as a plain label, not a picker: there is one network (docs/wallet-ux.md). */
@Composable
private fun NetworkLabel(network: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            network,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(ACCOUNTS_NETWORK_TAG),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * What the empty Accounts screen says: every way the + button adds an account, and why Create Safe
 * is greyed out there until an account whose key is on this device can pay for its deployment
 * (docs/wallet-ux.md, screen 2).
 */
internal const val NO_ACCOUNTS_HINT = "Add an account with the + button: watch an address, import a recovery " +
    "phrase or create an account. Create Safe is there too, and can be used once an account's key is on this " +
    "device, because that account pays for the Safe's deployment."

@Composable
private fun NoAccounts(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            NO_ACCOUNTS_HINT,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountList(
    accounts: List<AccountItem>,
    onAccountAction: (AccountAction) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag(ACCOUNTS_LIST_TAG)) {
        items(accounts, key = { it.address }) { account ->
            WithActionMenu(
                actions = accountMenu(account, onAccountAction, onUnavailable),
                menuLabel = "Actions for ${account.label}",
                onClick = { onAccountAction(AccountAction.Open(account.address)) },
                clickLabel = "Open ${account.label}",
            ) {
                AccountRow(account)
            }
            HorizontalDivider()
        }
    }
}

/**
 * The long-press menu of an account row: docs/wallet-ux.md, screen 3. Send greyed out where the
 * account cannot send, a tap saying why ([AccountItem.sendReason]); its detail screen has the
 * same as buttons.
 */
private fun accountMenu(
    account: AccountItem,
    act: (AccountAction) -> Unit,
    onUnavailable: (UnavailableNotice) -> Unit,
): List<MenuAction> {
    val address = account.address
    val sendReason = account.sendReason
    return listOf(
        MenuAction("Send", Icons.AutoMirrored.Filled.Send, unavailable = sendReason != null) {
            when {
                sendReason != null -> onUnavailable(UnavailableNotice("Send", sendReason))
                account.kind == AccountKind.SAFE -> act(AccountAction.Propose(address))
                else -> act(AccountAction.Send(address))
            }
        },
        MenuAction("Receive", SerotineIcons.QrCode) { act(AccountAction.Receive(address)) },
        MenuAction("Copy address", SerotineIcons.ContentCopy) { act(AccountAction.Copy(address)) },
        MenuAction("Rename", Icons.Filled.Edit) { act(AccountAction.Rename(address)) },
        MenuAction("Remove", Icons.Filled.Delete, destructive = true) { act(AccountAction.Remove(address)) },
    )
}

@Composable
private fun AccountRow(account: AccountItem) {
    ListItem(
        leadingContent = { AccountAvatar(account.address, threshold = account.signers) },
        headlineContent = { NameWithControlBadge(account.label, account.control) },
        supportingContent = {
            Column {
                Text(account.kindNote?.let { "${account.shortAddress} · $it" } ?: account.shortAddress)
                // The ENS name the row was named by, which no longer points here: why it shows the address.
                account.nameNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                // A balance last verified, not current: said under the row, where there is room.
                account.balanceNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    account.balance,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (account.balanceVerified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                account.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
