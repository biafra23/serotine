package eth.serotine.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.APP_VERSION

/** Test tag of the account list, for UI tests. */
const val HOME_ACCOUNT_LIST_TAG = "home-account-list"

/** Test tag of the light client's status strip, for UI tests. */
const val HOME_LIGHT_CLIENT_TAG = "home-light-client"

/** Test tag of the badge naming the network the app works on, for UI tests. */
const val HOME_NETWORK_TAG = "home-network"

/** Binds [HomeScreen] to its ViewModel: the only composable that knows the ViewModel exists. */
@Composable
fun HomeRoute(viewModel: HomeViewModel, onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, onAddAccount = onAddAccount, modifier = modifier)
}

/**
 * The Home screen as a pure function of [state]; stateless, so a test can render any state
 * directly. Its floating action button asks for [onAddAccount].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    // One merged semantics node: a screen reader announces "Serotine v0.1.0"
                    // rather than two unrelated labels.
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text("Serotine")
                        Text("v$APP_VERSION", style = MaterialTheme.typography.labelSmall)
                    }
                },
                // The one network the app works on, always in sight, so a testnet wallet
                // is never mistaken for a mainnet one.
                actions = { NetworkBadge(state.network, Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LightClientStrip(state.lightClient)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Rows(state)
            }
        }
    }
}

/** What the light client is doing, until and after it serves verified reads. */
@Composable
private fun LightClientStrip(item: LightClientItem, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(HOME_LIGHT_CLIENT_TAG)) {
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
private fun BoxScope.Rows(state: HomeUiState) {
    when (state) {
        is HomeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        is HomeUiState.Ready ->
            if (state.accounts.isEmpty()) {
                NoAccounts(Modifier.align(Alignment.Center))
            } else {
                AccountList(state.accounts)
            }
    }
}

@Composable
private fun NetworkBadge(network: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            network,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(HOME_NETWORK_TAG),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun NoAccounts(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add an account from its seed phrase with the + button.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountList(accounts: List<AccountItem>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag(HOME_ACCOUNT_LIST_TAG)) {
        items(accounts, key = { it.address }) { account ->
            ListItem(
                headlineContent = { Text(account.label) },
                supportingContent = { Text("${account.shortAddress} · ${account.kind.displayName}") },
                trailingContent = {
                    Text(
                        account.balance,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (account.balanceVerified) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

private val AccountKind.displayName: String
    get() = when (this) {
        AccountKind.EOA -> "Account"
        AccountKind.SAFE -> "Safe"
    }
