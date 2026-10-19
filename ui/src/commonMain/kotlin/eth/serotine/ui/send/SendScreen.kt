package eth.serotine.ui.send

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.FeeChoice
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.RecipientCard
import eth.serotine.ui.components.ReviewButton
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.TransferFormFields
import eth.serotine.ui.components.UnlockFields
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of the reviewed amount, for UI tests. */
const val SEND_AMOUNT_REVIEWED_TAG = "send-amount-reviewed"

/** Test tag of the wallet password field, for UI tests. */
const val SEND_PASSWORD_TAG = "send-password"

/** Test tag of the Sign and send button, for UI tests. */
const val SEND_SIGN_TAG = "send-sign"

/** Test tag of the button that opens an earlier transaction still maybe on its way, for UI tests. */
const val SEND_EARLIER_TAG = "send-earlier"

/**
 * Binds [SendScreen] to its ViewModel. A transfer broadcast ends the screen through [onSent]
 * with its hash, and [onOpenTransfer] opens the status of an earlier one that may still be on its
 * way; the screen stopping being shown becomes [SendViewModel.onHidden] (the password goes) and
 * being shown again [SendViewModel.onShown] (the review is quoted anew).
 */
@Composable
fun SendRoute(
    viewModel: SendViewModel,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on the seed screens, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? SendUiState.Sent)?.hash
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(sent) {
        if (sent != null) currentOnSent(sent)
    }
    SendScreen(
        state = state,
        onAssetChange = viewModel::onAssetChange,
        onToChange = viewModel::onToChange,
        onPickAccount = viewModel::onPickAccount,
        onRetryLookup = viewModel::onRetryLookup,
        onAmountChange = viewModel::onAmountChange,
        onMax = viewModel::onMax,
        onFeeChange = viewModel::onFeeChange,
        onReview = viewModel::review,
        onEdit = viewModel::edit,
        onRefresh = viewModel::refresh,
        onPasswordChange = viewModel::onPasswordChange,
        onSign = viewModel::sign,
        onSignWithBiometrics = viewModel::signWithBiometrics,
        onRetry = viewModel::retry,
        onOpenTransfer = onOpenTransfer,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Send (docs/wallet-ux.md, screens 7 and 8) as a pure function of [state]: the compose step —
 * from, asset, to, amount and fee — then the review of the verified quote, with the unlock that
 * signs it. While the review takes the wallet password, the window is kept out of screen capture
 * where the platform allows ([protectFromScreenCapture]); where it does not, the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SendScreen(
    state: SendUiState,
    onAssetChange: (contract: String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (address: String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state as? SendUiState.Review
    // In the review, back is to the compose step, until bytes may be out there: after a broadcast
    // no peer answered, only Try again may send them, so back leaves Send. While a signature
    // runs, back waits for it: leaving would not stop it, only hide what it did.
    val toCompose = review != null && review.notSent?.canRetry != true
    BackHandler(enabled = review != null) {
        when {
            review?.signing == true -> Unit
            toCompose -> onEdit()
            else -> onBack()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = if (toCompose) onEdit else onBack, enabled = review?.signing != true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        when (state) {
                            is SendUiState.Review -> "Review"
                            is SendUiState.Sent -> "Sent"
                            else -> "Send"
                        },
                    )
                },
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
                is SendUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SendUiState.Sent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is SendUiState.CannotSend -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SendUiState.Compose -> ComposeContent(
                    state,
                    onAssetChange,
                    onToChange,
                    onPickAccount,
                    onRetryLookup,
                    onAmountChange,
                    onMax,
                    onFeeChange,
                    onReview,
                    onOpenTransfer,
                )
                is SendUiState.Review ->
                    ReviewContent(state, onRefresh, onPasswordChange, onSign, onSignWithBiometrics, onRetry)
            }
        }
    }
}

@Composable
private fun ComposeContent(
    state: SendUiState.Compose,
    onAssetChange: (String?) -> Unit,
    onToChange: (String) -> Unit,
    onPickAccount: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TransferFormFields(state.form, onAssetChange, onToChange, onPickAccount, onRetryLookup, onAmountChange, onMax)
        Heading("Fee")
        FeeChoice(state.fee, enabled = !state.form.quoting, onFeeChange)
        Note("The fee is paid in the network's own currency; the review shows the most it can be.")
        state.form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.earlier?.let { hash ->
            OutlinedButton(onClick = { onOpenTransfer(hash) }, Modifier.testTag(SEND_EARLIER_TAG)) {
                Text("Open the earlier transaction")
            }
        }
        ReviewButton(state.form, onReview, Modifier.align(Alignment.End))
    }
}

@Composable
private fun ReviewContent(
    state: SendUiState.Review,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("You send")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(state.asset.icon, state.asset.symbol, size = 40.dp)
            SelectionContainer {
                Text(
                    state.amount,
                    Modifier.testTag(SEND_AMOUNT_REVIEWED_TAG),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        state.asset.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Heading("To")
        RecipientCard(state.recipient)
        Heading("From")
        FromLine(state.from)
        Heading("Network fee")
        Text("At most ${state.maxFee}")
        Note(state.feeDetail)
        Heading("Verified simulation")
        state.balances.forEach { change ->
            Column {
                Text(change.what, style = MaterialTheme.typography.labelLarge)
                Text("${change.before} → ${change.after}")
                Note(change.verifiedAt)
            }
        }
        Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
        state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onRefresh, enabled = state.idle) {
            if (state.refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            }
            Spacer(Modifier.size(8.dp))
            Text("Refresh the quote")
        }
        HorizontalDivider()
        val notSent = state.notSent
        if (notSent != null) {
            NotSentFields(notSent, state.signing, onRetry)
        } else {
            UnlockContent(state, onPasswordChange, onSign, onSignWithBiometrics)
        }
    }
}

/** The unlock: a fingerprint or face where the user turned it on, the wallet password always. */
@Composable
private fun UnlockContent(
    state: SendUiState.Review,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
) {
    UnlockFields(
        unlock = state.unlock,
        signing = state.signing,
        canSign = state.canSign,
        canSignWithBiometrics = state.canSignWithBiometrics,
        signLabel = "Sign and send",
        signingLabel = "Signing and sending…",
        onPasswordChange = onPasswordChange,
        onSign = onSign,
        onSignWithBiometrics = onSignWithBiometrics,
        passwordTag = SEND_PASSWORD_TAG,
        signTag = SEND_SIGN_TAG,
    )
}
