package eth.serotine.ui.safepropose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.SafeTxActionCard
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.send.FromLine
import eth.serotine.ui.send.Heading
import eth.serotine.ui.send.Note
import eth.serotine.ui.send.RecipientCard
import eth.serotine.ui.send.ReviewButton
import eth.serotine.ui.send.TransferFormFields
import kotlinx.coroutines.Dispatchers

/** Test tag of the reviewed amount, for UI tests. */
const val PROPOSE_AMOUNT_REVIEWED_TAG = "propose-amount-reviewed"

/** Test tag of the wallet password field, for UI tests. */
const val PROPOSE_PASSWORD_TAG = "propose-password"

/** Test tag of the Sign and queue button, for UI tests. */
const val PROPOSE_SIGN_TAG = "propose-sign"

/**
 * Binds [SafeProposeScreen] to its ViewModel. A proposal signed and queued ends the screen through
 * [onProposed] with its Safe transaction hash; the screen stopping being shown becomes
 * [SafeProposeViewModel.onHidden] (the password goes) and being shown again
 * [SafeProposeViewModel.onShown] (the review is proposed anew).
 */
@Composable
fun SafeProposeRoute(
    viewModel: SafeProposeViewModel,
    onBack: () -> Unit,
    onProposed: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val proposed = (state as? SafeProposeUiState.Proposed)?.hash
    val currentOnProposed by rememberUpdatedState(onProposed)
    LaunchedEffect(proposed) {
        if (proposed != null) currentOnProposed(proposed)
    }
    SafeProposeScreen(
        state = state,
        actions = SafeProposeActions(
            onAssetChange = viewModel::onAssetChange,
            onToChange = viewModel::onToChange,
            onPickAccount = viewModel::onPickAccount,
            onRetryLookup = viewModel::onRetryLookup,
            onAmountChange = viewModel::onAmountChange,
            onMax = viewModel::onMax,
            onReview = viewModel::review,
            onEdit = viewModel::edit,
            onRefresh = viewModel::refresh,
            onOwnerChange = viewModel::onOwnerChange,
            onPasswordChange = viewModel::onPasswordChange,
            onSign = viewModel::sign,
            onSignWithBiometrics = viewModel::signWithBiometrics,
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * What the user can ask of [SafeProposeScreen]: Send's form (the asset, the recipient typed or
 * picked, the amount, Max, Review), and in the review back to the form ([onEdit]), Refresh, the
 * owner who signs, the password, and Sign ([onSign], or [onSignWithBiometrics]).
 */
class SafeProposeActions(
    val onAssetChange: (contract: String?) -> Unit = {},
    val onToChange: (String) -> Unit = {},
    val onPickAccount: (address: String) -> Unit = {},
    val onRetryLookup: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onMax: () -> Unit = {},
    val onReview: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOwnerChange: (address: String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSign: () -> Unit = {},
    val onSignWithBiometrics: () -> Unit = {},
)

/**
 * Send for a Safe (docs/wallet-ux.md, screens 7 and 16) as a pure function of [state]: Send's form
 * — from the Safe, asset, to, amount; no fee, which the account that executes it pays — then the
 * review of the proposal: the Safe transaction it is, decoded here, its nonce and those queued
 * before it, the Safe's balance before, queued and after, whether its call was simulated, and the
 * owner held here who signs it. While the review takes the wallet password the window is kept
 * out of screen capture where the platform allows; where it does not, the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SafeProposeScreen(
    state: SafeProposeUiState,
    actions: SafeProposeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state as? SafeProposeUiState.Review
    // In the review, back is to the form; while a signature runs, back waits for it.
    BackHandler(enabled = review != null) {
        if (review?.signing != true) actions.onEdit()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = if (review != null) actions.onEdit else onBack,
                        enabled = review?.signing != true,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (review != null) "Review proposal" else "Send from a Safe") },
                actions = {
                    Text(
                        state.network,
                        Modifier.padding(end = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is SafeProposeUiState.Loading, is SafeProposeUiState.Proposed ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SafeProposeUiState.CannotPropose -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SafeProposeUiState.Compose -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val form = state.form
                    TransferFormFields(
                        form,
                        actions.onAssetChange,
                        actions.onToChange,
                        actions.onPickAccount,
                        actions.onRetryLookup,
                        actions.onAmountChange,
                        actions.onMax,
                    )
                    Note(
                        "A Safe transaction: the owners sign it, then any account of theirs executes it and pays its " +
                            "fee. Nothing is queued until an owner held here signs it.",
                    )
                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ReviewButton(form, actions.onReview, Modifier.align(Alignment.End))
                }
                is SafeProposeUiState.Review -> ReviewContent(state, actions)
            }
        }
    }
}

@Composable
private fun ReviewContent(state: SafeProposeUiState.Review, actions: SafeProposeActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("The Safe sends")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer {
                Text(
                    state.amount,
                    Modifier.testTag(PROPOSE_AMOUNT_REVIEWED_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        RecipientCard(state.recipient)
        Heading("From")
        FromLine(state.from)
        Heading("The Safe transaction, decoded here")
        SafeTxActionCard(state.action)
        Text(state.nonce, style = MaterialTheme.typography.titleSmall)
        state.queued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Heading("The Safe's balance")
        Column {
            Text(state.balance.what, style = MaterialTheme.typography.labelLarge)
            Text(state.balance.before)
            state.balance.queued?.let { Text("− $it") }
            Text("→ ${state.balance.after}")
            Note(state.balance.verifiedAt)
        }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = actions.onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Propose it again")
        }
        HorizontalDivider()
        Heading("Signed by")
        Column {
            state.owners.forEach { owner ->
                val selected = owner.address == state.owner
                ListItem(
                    modifier = Modifier.selectable(
                        selected = selected,
                        enabled = state.idle,
                        role = Role.RadioButton,
                        onClick = { actions.onOwnerChange(owner.address) },
                    ),
                    leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
                    headlineContent = { Text(owner.label) },
                    supportingContent = { Text(shortAddress(owner.address), fontFamily = FontFamily.Monospace) },
                    trailingContent = { RadioButton(selected = selected, onClick = null, enabled = state.idle) },
                )
            }
        }
        Note("Signed, it is queued on this device, from where it is shared with the other owners.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = "Sign and queue",
            signingLabel = "Signing…",
            onPasswordChange = actions.onPasswordChange,
            onSign = actions.onSign,
            onSignWithBiometrics = actions.onSignWithBiometrics,
            passwordTag = PROPOSE_PASSWORD_TAG,
            signTag = PROPOSE_SIGN_TAG,
        )
    }
}
