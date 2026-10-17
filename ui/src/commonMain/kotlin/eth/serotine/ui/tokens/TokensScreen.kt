package eth.serotine.ui.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.ActionDropdown
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.launch

/** Test tag of each token row, for UI tests. */
const val TOKENS_ROW_TAG = "tokens-row"

/** Binds [TokensScreen] to its ViewModel; Copy is done here, on the platform's clipboard. */
@Composable
fun TokensRoute(viewModel: TokensViewModel, onBack: () -> Unit, onAddToken: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TokensScreen(
        state,
        onBack = onBack,
        onAddToken = onAddToken,
        onCopy = rememberCopyText(),
        onSetHidden = viewModel::setHidden,
        onRemove = viewModel::remove,
        modifier = modifier,
    )
}

/**
 * Settings → Tokens as a pure function of [state]: every known token, each with what its
 * check says and a menu (Copy contract, Hide or Show, Remove for an added one) behind a
 * visible button, and Add token.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokensScreen(
    state: TokensUiState,
    onBack: () -> Unit,
    onAddToken: () -> Unit,
    onCopy: (String) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
                title = { Text("Tokens") },
            )
        },
        floatingActionButton = {
            // The content overload: the icon-and-text one leaves its label out of the
            // button's merged semantics, so a screen reader would say only "Button".
            ExtendedFloatingActionButton(onClick = onAddToken) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Add token")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            state.notice?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            state.items.forEach { item ->
                TokenRow(
                    item,
                    onCopy = {
                        onCopy(item.address)
                        scope.launch { snackbar.showSnackbar("Contract address copied") }
                    },
                    onSetHidden = { onSetHidden(item.address, it) },
                    onRemove = { onRemove(item.address) },
                )
            }
            Text(
                "A token is known by its contract address. Added tokens are described as their " +
                    "contracts describe themselves; nothing is fetched from a token list.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenRow(item: TokenItem, onCopy: () -> Unit, onSetHidden: (Boolean) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.testTag(TOKENS_ROW_TAG),
        leadingContent = { TokenIcon(item.icon, item.symbol) },
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.symbol)
                if (item.hidden) {
                    Text(
                        "Hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text("${item.name} · ${shortAddress(item.address)}")
                Text(
                    item.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.problem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                item.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${item.symbol}")
                }
                ActionDropdown(
                    actions = listOfNotNull(
                        MenuAction("Copy contract", SerotineIcons.ContentCopy, onClick = onCopy),
                        if (item.hidden) {
                            MenuAction("Show token", SerotineIcons.Visibility) { onSetHidden(false) }
                        } else {
                            MenuAction("Hide token", SerotineIcons.Visibility) { onSetHidden(true) }
                        },
                        if (item.builtIn) {
                            null
                        } else {
                            MenuAction("Remove", Icons.Filled.Delete, destructive = true, onClick = onRemove)
                        },
                    ),
                    expanded = menu,
                    onDismiss = { menu = false },
                )
            }
        },
    )
}
