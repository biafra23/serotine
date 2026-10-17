package eth.serotine.ui.accountdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.ActionDropdown
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.launch

/** Test tag of the account's balance, for UI tests. */
const val ACCOUNT_BALANCE_TAG = "account-balance"

/** Test tag of the account's full address, for UI tests. */
const val ACCOUNT_ADDRESS_TAG = "account-address"

/** Test tag of each asset row, for UI tests. */
const val ACCOUNT_ASSET_TAG = "account-asset"

/**
 * Binds [AccountDetailScreen] to its ViewModel. An account that leaves the wallet while the
 * screen is shown (removed from here or elsewhere) ends the screen through [onGone] — once
 * the screen is the one in front: under the remove dialog it waits, so the dialog can say
 * how the removal went before both close.
 */
@Composable
fun AccountDetailRoute(
    viewModel: AccountDetailViewModel,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onReceive: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    // A dialog destination over this one holds it at STARTED; it is RESUMED when in front.
    val inFront = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    val leave = state is AccountDetailUiState.Gone && inFront
    LaunchedEffect(leave) {
        if (leave) gone()
    }
    AccountDetailScreen(
        state = state,
        onBack = onBack,
        onReceive = onReceive,
        onCopy = rememberCopyText(),
        onRename = onRename,
        onRemove = onRemove,
        onHideToken = viewModel::hideToken,
        onMessageShown = viewModel::messageShown,
        modifier = modifier,
    )
}

/**
 * One account (docs/wallet-ux.md, screen 4) as a pure function of [state]: its blockie,
 * balance, address and control, Receive as a button, and Copy, Rename and Remove in the top
 * bar's menu, so none of them depends on the long press of the account list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    state: AccountDetailUiState,
    onBack: () -> Unit,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onHideToken: (contract: String, symbol: String) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message = (state as? AccountDetailUiState.Ready)?.message
    val shown by rememberUpdatedState(onMessageShown)
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            shown()
        }
    }
    val copy: (String) -> Unit = { address ->
        onCopy(address)
        scope.launch { snackbar.showSnackbar("Address copied") }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text((state as? AccountDetailUiState.Ready)?.label ?: "") },
                actions = {
                    if (state is AccountDetailUiState.Ready) {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        ActionDropdown(
                            actions = listOf(
                                MenuAction("Copy address", SerotineIcons.ContentCopy) { copy(state.address) },
                                MenuAction("Rename", Icons.Filled.Edit, onClick = onRename),
                                MenuAction("Remove", Icons.Filled.Delete, destructive = true, onClick = onRemove),
                            ),
                            expanded = menu,
                            onDismiss = { menu = false },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                AccountDetailUiState.Loading, AccountDetailUiState.Gone ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is AccountDetailUiState.Unavailable -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                is AccountDetailUiState.Ready -> Details(
                    state,
                    onReceive,
                    copy,
                    onCopyContract = { contract ->
                        onCopy(contract)
                        scope.launch { snackbar.showSnackbar("Contract address copied") }
                    },
                    onHideToken = { asset -> asset.contract?.let { onHideToken(it, asset.symbol) } },
                )
            }
        }
    }
}

@Composable
private fun Details(
    state: AccountDetailUiState.Ready,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(state.address, size = 56.dp)
            Column {
                Text(
                    state.balance,
                    Modifier.testTag(ACCOUNT_BALANCE_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.balanceVerified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlBadge(state.control)
                    if (state.kind == AccountKind.SAFE) Text("Safe", style = MaterialTheme.typography.labelMedium)
                }
                // Right under the amount it dates, so it cannot be read as the tokens' block.
                state.balanceNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // In full, as docs/wallet-ux.md's screen 4 has it, with Copy beside it.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    state.address,
                    Modifier.testTag(ACCOUNT_ADDRESS_TAG),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = { onCopy(state.address) }) {
                Icon(SerotineIcons.ContentCopy, contentDescription = "Copy address", Modifier.size(18.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onReceive) {
                Icon(SerotineIcons.QrCode, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Receive")
            }
        }
        Assets(state, onCopyContract, onHideToken)
    }
}

/**
 * The asset rows (docs/wallet-ux.md, screen 4), each with the block its amount was verified
 * at, and what is not known yet about the other tokens.
 */
@Composable
private fun Assets(
    state: AccountDetailUiState.Ready,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.assets.forEach { asset ->
            val contract = asset.contract
            if (contract == null) {
                AssetItem(asset)
            } else {
                // Long press (right click): docs/wallet-ux.md, screen 5. Both actions are also
                // in Settings → Tokens, behind a visible button. (Send arrives with sending.)
                WithActionMenu(
                    actions = listOf(
                        MenuAction("Copy contract", SerotineIcons.ContentCopy) { onCopyContract(contract) },
                        MenuAction("Hide token", SerotineIcons.Visibility) { onHideToken(asset) },
                    ),
                    menuLabel = "Actions for ${asset.symbol}",
                    onClick = {},
                ) {
                    AssetItem(asset)
                }
            }
        }
        state.tokensNote?.let { note ->
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssetItem(asset: AssetRow) {
    ListItem(
        modifier = Modifier.testTag(ACCOUNT_ASSET_TAG),
        leadingContent = { TokenIcon(asset.icon, asset.symbol) },
        headlineContent = { Text(asset.symbol) },
        supportingContent = {
            Column {
                Text(asset.verifiedAt?.let { "${asset.name} · verified at $it" } ?: asset.name)
                asset.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Text(
                asset.amount,
                style = MaterialTheme.typography.bodyLarge,
                color = if (asset.verified) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}
