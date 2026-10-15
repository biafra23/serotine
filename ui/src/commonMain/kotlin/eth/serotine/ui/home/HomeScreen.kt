package eth.serotine.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

/** Binds [HomeScreen] to its ViewModel: the only composable that knows the ViewModel exists. */
@Composable
fun HomeRoute(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, modifier = modifier)
}

/** The Home screen as a pure function of [state]; stateless, so a test can render any state directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
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
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                HomeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is HomeUiState.Ready ->
                    if (state.accounts.isEmpty()) {
                        NoAccounts(Modifier.align(Alignment.Center))
                    } else {
                        AccountList(state.accounts)
                    }
            }
        }
    }
}

@Composable
private fun NoAccounts(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No accounts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add an account or a Safe to get started.",
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
                supportingContent = { Text(account.shortAddress) },
                trailingContent = { Text(account.kind.displayName) },
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
