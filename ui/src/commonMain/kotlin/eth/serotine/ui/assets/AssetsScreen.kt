package eth.serotine.ui.assets

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.SpeedDialAction
import eth.serotine.ui.components.SpeedDialFab
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.launch

/** Test tag of the account list, for UI tests. */
const val ASSETS_ACCOUNT_LIST_TAG = "assets-account-list"

/** Test tag of the notice when the stored list is not what is shown: set aside, or unavailable. */
const val ASSETS_NOTICE_TAG = "assets-notice"

/** Test tag of the light client's status strip, for UI tests. */
const val ASSETS_LIGHT_CLIENT_TAG = "assets-light-client"

/** Test tag of the label naming the network the app works on, for UI tests. */
const val ASSETS_NETWORK_TAG = "assets-network"

/** Test tag of the portfolio's total, for UI tests. */
const val ASSETS_TOTAL_TAG = "assets-total"

/**
 * Binds [AssetsScreen] to its ViewModel: the only composable that knows the ViewModel
 * exists. Copy is done here, on the platform's clipboard; every other action is
 * [onAccountAction]'s, which navigates.
 */
@Composable
fun AssetsRoute(
    viewModel: AssetsViewModel,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copy = rememberCopyText()
    AssetsScreen(
        state = state,
        onWatchAddress = onWatchAddress,
        onImportPhrase = onImportPhrase,
        onAccountAction = { action ->
            if (action is AccountAction.Copy) copy(action.address) else onAccountAction(action)
        },
        modifier = modifier,
    )
}

/**
 * The Assets screen as a pure function of [state]; stateless, so a test can render any
 * state directly. Its floating action button fans out into Watch address ([onWatchAddress])
 * and Import recovery phrase ([onImportPhrase]); a row's tap and its long-press menu (a
 * right click on desktop) ask [onAccountAction].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    state: AssetsUiState,
    onWatchAddress: () -> Unit,
    onImportPhrase: () -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
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
            if (state !is AssetsUiState.ListUnavailable) {
                SpeedDialFab(
                    actions = listOf(
                        SpeedDialAction("Watch address", SerotineIcons.Visibility, onWatchAddress),
                        SpeedDialAction("Import recovery phrase", SerotineIcons.Download, onImportPhrase),
                    ),
                    description = "Add account",
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
                // The one network the app works on, always in sight, so a testnet wallet
                // is never mistaken for a mainnet one.
                actions = { NetworkLabel(state.network, Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LightClientStrip(state.lightClient)
            (state as? AssetsUiState.Ready)?.let { ready -> ready.total?.let { PortfolioTotal(it, ready.totalNote) } }
            (state as? AssetsUiState.Ready)?.notice?.let { notice ->
                Text(
                    notice,
                    Modifier.padding(16.dp).testTag(ASSETS_NOTICE_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Rows(state, act)
            }
        }
    }
}

/** The portfolio's total in the display currency, and what it leaves out (docs/wallet-ux.md, screen 1). */
@Composable
private fun PortfolioTotal(total: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(total, Modifier.testTag(ASSETS_TOTAL_TAG), style = MaterialTheme.typography.headlineMedium)
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the light client is doing, until and after it serves verified reads. */
@Composable
private fun LightClientStrip(item: LightClientItem, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(ASSETS_LIGHT_CLIENT_TAG)) {
        Text(
            item.text,
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            item.progress != null -> LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            item.working -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> HorizontalDivider()
        }
    }
}

@Composable
private fun BoxScope.Rows(state: AssetsUiState, onAccountAction: (AccountAction) -> Unit) {
    when (state) {
        is AssetsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is AssetsUiState.ListUnavailable -> Text(
            state.message,
            Modifier.align(Alignment.Center).padding(24.dp).testTag(ASSETS_NOTICE_TAG),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        is AssetsUiState.Ready ->
            if (state.accounts.isEmpty()) {
                NoAccounts(Modifier.align(Alignment.Center))
            } else {
                AccountList(state.accounts, onAccountAction)
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
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(ASSETS_NETWORK_TAG),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun NoAccounts(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add an account with the + button: watch an address, or import a recovery phrase.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountList(
    accounts: List<AccountItem>,
    onAccountAction: (AccountAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag(ASSETS_ACCOUNT_LIST_TAG)) {
        items(accounts, key = { it.address }) { account ->
            WithActionMenu(
                actions = accountMenu(account.address, onAccountAction),
                menuLabel = "Actions for ${account.label}",
                onClick = { onAccountAction(AccountAction.Open(account.address)) },
            ) {
                AccountRow(account)
            }
            HorizontalDivider()
        }
    }
}

/** The long-press menu of an account row: docs/wallet-ux.md, screen 3 (Send arrives with sending). */
private fun accountMenu(address: String, act: (AccountAction) -> Unit): List<MenuAction> = listOf(
    MenuAction("Receive", SerotineIcons.QrCode) { act(AccountAction.Receive(address)) },
    MenuAction("Copy address", SerotineIcons.ContentCopy) { act(AccountAction.Copy(address)) },
    MenuAction("Rename", Icons.Filled.Edit) { act(AccountAction.Rename(address)) },
    MenuAction("Remove", Icons.Filled.Delete, destructive = true) { act(AccountAction.Remove(address)) },
)

@Composable
private fun AccountRow(account: AccountItem) {
    ListItem(
        leadingContent = { AccountAvatar(account.address) },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(account.label)
                ControlBadge(account.control)
            }
        },
        // Until a Safe's threshold overlay can be drawn, the kind says it is one.
        supportingContent = {
            Text(if (account.kind == AccountKind.SAFE) "${account.shortAddress} · Safe" else account.shortAddress)
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
