package eth.serotine.ui.swap

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AssetChoiceRow
import eth.serotine.ui.components.BalanceChange
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.FeeChoice
import eth.serotine.ui.components.FromLine
import eth.serotine.ui.components.Heading
import eth.serotine.ui.components.NotSentFields
import eth.serotine.ui.components.Note
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.UnlockFields
import kotlinx.coroutines.Dispatchers

/** Test tag of each asset to sell, for UI tests. */
const val SWAP_SELL_TAG = "swap-sell"

/** Test tag of each asset to buy, for UI tests. */
const val SWAP_BUY_TAG = "swap-buy"

/** Test tag of the amount field, for UI tests. */
const val SWAP_AMOUNT_TAG = "swap-amount"

/** Test tag of the tolerance field, for UI tests. */
const val SWAP_TOLERANCE_TAG = "swap-tolerance"

/** Test tag of the Review button, for UI tests. */
const val SWAP_REVIEW_TAG = "swap-review"

/** Test tag of the reviewed minimum, for UI tests. */
const val SWAP_MINIMUM_TAG = "swap-minimum"

/** Test tag of the wallet password field, for UI tests. */
const val SWAP_PASSWORD_TAG = "swap-password"

/** Test tag of the button that signs, for UI tests. */
const val SWAP_SIGN_TAG = "swap-sign"

/** Test tag of the button that opens the transaction holding the swap back, for UI tests. */
const val SWAP_EARLIER_TAG = "swap-earlier"

/**
 * Binds [SwapScreen] to its ViewModel. A swap broadcast ends the screen through [onSent] with its
 * hash, and [onOpenTransfer] opens the status of a transaction that holds the swap back (its
 * approval, an earlier swap, one that may still be on its way); the screen stopping being shown
 * becomes [SwapViewModel.onHidden] (the password goes) and being shown again
 * [SwapViewModel.onShown] (the review is quoted anew).
 */
@Composable
fun SwapRoute(
    viewModel: SwapViewModel,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Main.immediate: as on Send, an edit reaches its field in the same frame.
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onShown() }
    val sent = (state as? SwapUiState.Sent)?.hash
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(sent) {
        if (sent != null) currentOnSent(sent)
    }
    SwapScreen(
        state = state,
        onSellChange = viewModel::onSellChange,
        onBuyChange = viewModel::onBuyChange,
        onAmountChange = viewModel::onAmountChange,
        onMax = viewModel::onMax,
        onToleranceChange = viewModel::onToleranceChange,
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
 * Swap (docs/swaps.md; screens 22 and 23) as a pure function of [state]: the compose step — sell,
 * buy, amount, tolerance and fee — then the review of the approval the swap needs first, if any,
 * and of the swap, each with the unlock that signs it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SwapScreen(
    state: SwapUiState,
    onSellChange: (contract: String?) -> Unit,
    onBuyChange: (contract: String?) -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onToleranceChange: (String) -> Unit,
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
    val review = state as? Signable
    // As on Send: back from a review is to the compose step, until bytes may be out there, and
    // waits while a signature runs.
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
                            is SwapUiState.ApprovalReview -> "Approve"
                            is SwapUiState.SwapReview -> "Review swap"
                            is SwapUiState.Sent -> "Sent"
                            else -> "Swap"
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
                is SwapUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is SwapUiState.Sent -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Sent", Modifier.padding(top = 12.dp))
                }
                is SwapUiState.CannotSwap -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                is SwapUiState.Compose -> ComposeContent(
                    state,
                    onSellChange,
                    onBuyChange,
                    onAmountChange,
                    onMax,
                    onToleranceChange,
                    onFeeChange,
                    onReview,
                    onOpenTransfer,
                )
                is SwapUiState.ApprovalReview -> ReviewColumn {
                    ApprovalContent(state)
                    ReviewFooter(
                        state,
                        "Sign and send the approval",
                        onRefresh,
                        onPasswordChange,
                        onSign,
                        onSignWithBiometrics,
                        onRetry,
                    )
                }
                is SwapUiState.SwapReview -> ReviewColumn {
                    SwapContent(state)
                    ReviewFooter(
                        state,
                        "Sign and swap",
                        onRefresh,
                        onPasswordChange,
                        onSign,
                        onSignWithBiometrics,
                        onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeContent(
    state: SwapUiState.Compose,
    onSellChange: (String?) -> Unit,
    onBuyChange: (String?) -> Unit,
    onAmountChange: (String) -> Unit,
    onMax: () -> Unit,
    onToleranceChange: (String) -> Unit,
    onFeeChange: (FeeLevel) -> Unit,
    onReview: () -> Unit,
    onOpenTransfer: (hash: String) -> Unit,
) {
    val form = state.form
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("From")
        FromLine(form.from)
        Heading("Sell")
        Column {
            form.sellOptions.forEach { asset ->
                AssetChoiceRow(asset, selected = asset == form.sell, enabled = !form.quoting, tag = SWAP_SELL_TAG) {
                    onSellChange(asset.contract)
                }
            }
            form.note?.let { Note(it) }
        }
        Heading("Amount")
        OutlinedTextField(
            value = form.amount,
            onValueChange = onAmountChange,
            modifier = Modifier.fillMaxWidth().testTag(SWAP_AMOUNT_TAG),
            enabled = !form.quoting && form.sell != null,
            label = { Text(form.sell?.let { "Amount of ${it.symbol} to sell" } ?: "Amount") },
            singleLine = true,
            trailingIcon = { TextButton(onClick = onMax, enabled = form.canMax) { Text("Max") } },
            isError = form.amountError != null,
            supportingText = (form.amountError ?: form.amountHint)?.let { text -> { Text(text) } },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal),
        )
        Heading("Buy")
        Column {
            form.buyOptions.forEach { asset ->
                AssetChoiceRow(asset, selected = asset == form.buy, enabled = !form.quoting, tag = SWAP_BUY_TAG) {
                    onBuyChange(asset.contract)
                }
            }
            Note("Only the assets your wallet lists: what a swap buys shows on Assets and can be sent.")
        }
        Heading("Slippage tolerance")
        OutlinedTextField(
            value = form.tolerance,
            onValueChange = onToleranceChange,
            modifier = Modifier.fillMaxWidth().testTag(SWAP_TOLERANCE_TAG),
            enabled = !form.quoting,
            label = { Text("Tolerance in %") },
            singleLine = true,
            isError = form.toleranceError != null,
            supportingText = {
                Text(form.toleranceError ?: "How far below the verified quote the swap may return: 0.1 % to 5 %.")
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal),
        )
        Heading("Fee")
        FeeChoice(state.fee, enabled = !form.quoting, onFeeChange)
        Note("The fee is paid in the network's own currency; the review shows the most it can be.")
        form.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.earlier?.let { earlier ->
            OutlinedButton(onClick = { onOpenTransfer(earlier.hash) }, Modifier.testTag(SWAP_EARLIER_TAG)) {
                Text(earlier.label)
            }
        }
        Button(
            onClick = onReview,
            enabled = form.canReview,
            modifier = Modifier.align(Alignment.End).testTag(SWAP_REVIEW_TAG),
        ) {
            if (form.quoting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Checking on chain…")
            } else {
                Text("Review")
            }
        }
    }
}

@Composable
private fun ReviewColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/** The approval the swap needs first: the token, the amount, the router it lets take it. */
@Composable
private fun ApprovalContent(state: SwapUiState.ApprovalReview) {
    Text(
        "Before the swap, the router needs your approval to take the ${state.token.symbol} it sells: its own " +
            "transaction, for this amount exactly.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Heading("Allow")
    Spender(state.spender)
    Heading("To take")
    AmountLine(state.token.icon, state.token.symbol, state.amount)
    Note("It may take ${state.allowance} now.")
    state.resetNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Heading("From")
    FromLine(state.from)
    Fee(state.maxFee, state.feeDetail)
    Heading("Verified simulation")
    Balance(state.balance)
    Text(state.simulation, style = MaterialTheme.typography.bodyMedium)
}

/** The swap: what it sells, what it returns and at least, through which pools, until when. */
@Composable
private fun SwapContent(state: SwapUiState.SwapReview) {
    Heading("You sell")
    AmountLine(state.sell.icon, state.sell.symbol, state.sold)
    Heading("You receive")
    AmountLine(state.buy.icon, state.buy.symbol, state.receive)
    Note(state.ranAt)
    SelectionContainer {
        Text(
            state.minimum,
            Modifier.testTag(SWAP_MINIMUM_TAG),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Note(state.tolerance)
    Heading("Route")
    state.route.forEach { step ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Blockie(step.pool, size = 24.dp)
            Column {
                Text("${step.pair} · ${step.fee}", style = MaterialTheme.typography.bodyMedium)
                Text(step.pool, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
    Note(state.routeNote)
    state.netNote?.let { Note(it) }
    Heading("Through")
    Spender(state.spender)
    state.allowanceNote?.let { Note(it) }
    Note(state.deadline)
    Heading("From, and paid to")
    FromLine(state.from)
    Fee(state.maxFee, state.feeDetail)
    Heading("Verified simulation")
    state.balances.forEach { Balance(it) }
    state.pending?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun AmountLine(icon: String?, symbol: String, amount: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenIcon(icon, symbol, size = 40.dp)
        SelectionContainer { Text(amount, style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun Spender(spender: SpenderItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Blockie(spender.address, size = 32.dp)
        Column {
            Text(spender.name, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(spender.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Fee(maxFee: String, detail: String) {
    Heading("Network fee")
    Text("At most $maxFee")
    Note(detail)
}

@Composable
private fun Balance(change: BalanceChange) {
    Column {
        Text(change.what, style = MaterialTheme.typography.labelLarge)
        Text("${change.before} → ${change.after}")
        Note(change.verifiedAt)
    }
}

/** Refresh, then the unlock that signs, or what became of a signature not broadcast. */
@Composable
private fun ReviewFooter(
    state: Signable,
    signLabel: String,
    onRefresh: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onSign: () -> Unit,
    onSignWithBiometrics: () -> Unit,
    onRetry: () -> Unit,
) {
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
        UnlockFields(
            unlock = state.unlock,
            signing = state.signing,
            canSign = state.canSign,
            canSignWithBiometrics = state.canSignWithBiometrics,
            signLabel = signLabel,
            signingLabel = "Signing and sending…",
            onPasswordChange = onPasswordChange,
            onSign = onSign,
            onSignWithBiometrics = onSignWithBiometrics,
            passwordTag = SWAP_PASSWORD_TAG,
            signTag = SWAP_SIGN_TAG,
        )
    }
}
