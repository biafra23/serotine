package eth.serotine.ui.send

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.domain.repository.FeeLevel
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.platform.protectFromScreenCapture
import kotlinx.coroutines.Dispatchers

/** Test tag of each asset choice, for UI tests. */
const val SEND_ASSET_TAG = "send-asset"

/** Test tag of the recipient field, for UI tests. */
const val SEND_TO_TAG = "send-to"

/** Test tag of the amount field, for UI tests. */
const val SEND_AMOUNT_TAG = "send-amount"

/** Test tag of the Review button, for UI tests. */
const val SEND_REVIEW_TAG = "send-review"

/** Test tag of the recipient's full address on the compose step and the review, for UI tests. */
const val SEND_RECIPIENT_TAG = "send-recipient"

/** Test tag of the reviewed amount, for UI tests. */
const val SEND_AMOUNT_REVIEWED_TAG = "send-amount-reviewed"

/** Test tag of the wallet password field, for UI tests. */
const val SEND_PASSWORD_TAG = "send-password"

/** Test tag of the Sign and send button, for UI tests. */
const val SEND_SIGN_TAG = "send-sign"

/**
 * Binds [SendScreen] to its ViewModel. A transfer broadcast ends the screen through [onSent]
 * with its hash; the screen stopping being shown becomes [SendViewModel.onHidden] (the password
 * goes) and being shown again [SendViewModel.onShown] (the review is quoted anew).
 */
@Composable
fun SendRoute(
    viewModel: SendViewModel,
    onBack: () -> Unit,
    onSent: (hash: String) -> Unit,
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
@OptIn(ExperimentalMaterial3Api::class)
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state as? SendUiState.Review
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // In the review, back is to the compose step, until something was signed.
                    val inReview = review != null && review.notSent == null
                    IconButton(onClick = if (inReview) onEdit else onBack, enabled = review?.signing != true) {
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
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("From")
        FromLine(state.from)
        Heading("Asset")
        Column {
            state.assets.forEach { asset ->
                AssetOption(asset, selected = asset == state.asset, enabled = !state.quoting) {
                    onAssetChange(asset.contract)
                }
            }
            state.assetNote?.let { Note(it) }
        }
        Heading("To")
        RecipientField(state, onToChange)
        state.recipient?.let { RecipientCard(it) }
        if (state.canRetryLookup) {
            TextButton(onClick = onRetryLookup) { Text("Try again") }
        }
        if (state.accounts.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.accounts.forEach { account ->
                    AssistChip(
                        onClick = { onPickAccount(account.address) },
                        enabled = !state.quoting,
                        label = { Text("${account.label} · ${shortAddress(account.address)}") },
                        leadingIcon = { Blockie(account.address, size = 18.dp) },
                    )
                }
            }
        }
        Heading("Amount")
        AmountField(state, onAmountChange, onMax)
        Heading("Fee")
        FeeChoice(state.fee, enabled = !state.quoting, onFeeChange)
        Note("The fee is paid in the network's own currency; the review shows the most it can be.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onReview,
            enabled = state.canReview,
            modifier = Modifier.align(Alignment.End).testTag(SEND_REVIEW_TAG),
        ) {
            if (state.quoting) {
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
private fun AssetOption(asset: AssetChoice, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier
            .testTag(SEND_ASSET_TAG)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { TokenIcon(asset.icon, asset.symbol) },
        headlineContent = { Text(asset.symbol) },
        supportingContent = {
            Column {
                Text(asset.verifiedAt?.let { "${asset.balance} · verified at $it" } ?: asset.balance)
                asset.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        // The row is the control; the radio button only shows which one is chosen.
        trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

@Composable
private fun RecipientField(state: SendUiState.Compose, onToChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.to,
        onValueChange = onToChange,
        modifier = Modifier.fillMaxWidth().testTag(SEND_TO_TAG),
        enabled = !state.quoting,
        label = { Text("Address or ENS name") },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        // The blockie once the text is an address, typed or resolved.
        leadingIcon = state.recipient?.let { recipient -> { Blockie(recipient.address, size = 24.dp) } },
        isError = state.toError,
        supportingText = { Text(state.toNote ?: "An address, an ENS name, or one of your accounts below") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
    )
}

/** The recipient in full, with what is known of it: the name it was resolved from, its name in the wallet. */
@Composable
private fun RecipientCard(recipient: RecipientItem) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(recipient.address, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = listOfNotNull(recipient.name, recipient.label?.let { "$it, in your wallet" })
                if (title.isNotEmpty()) Text(title.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                // In full: this is the address the user confirms, and what is signed.
                SelectionContainer {
                    Text(
                        recipient.address,
                        Modifier.testTag(SEND_RECIPIENT_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                recipient.resolvedAt?.let { Note(it) }
                recipient.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AmountField(state: SendUiState.Compose, onAmountChange: (String) -> Unit, onMax: () -> Unit) {
    OutlinedTextField(
        value = state.amount,
        onValueChange = onAmountChange,
        modifier = Modifier.fillMaxWidth().testTag(SEND_AMOUNT_TAG),
        enabled = !state.quoting && state.asset != null,
        label = { Text(state.asset?.let { "Amount in ${it.symbol}" } ?: "Amount") },
        singleLine = true,
        trailingIcon = { TextButton(onClick = onMax, enabled = state.canMax) { Text("Max") } },
        isError = state.amountError != null,
        supportingText = (state.amountError ?: state.amountHint)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal),
    )
}

/** Fee: Slow | Normal | Fast (docs/wallet-ux.md, screen 7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeeChoice(fee: FeeLevel, enabled: Boolean, onFeeChange: (FeeLevel) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FeeLevel.entries.forEachIndexed { index, level ->
            SegmentedButton(
                selected = fee == level,
                onClick = { onFeeChange(level) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, FeeLevel.entries.size),
                label = {
                    Text(
                        when (level) {
                            FeeLevel.SLOW -> "Slow"
                            FeeLevel.NORMAL -> "Normal"
                            FeeLevel.FAST -> "Fast"
                        },
                    )
                },
            )
        }
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
            NotSentContent(notSent, state.signing, onRetry)
        } else {
            UnlockContent(state, onPasswordChange, onSign, onSignWithBiometrics)
        }
    }
}

@Composable
private fun NotSentContent(notSent: NotSentItem, retrying: Boolean, onRetry: () -> Unit) {
    Text(notSent.message, color = MaterialTheme.colorScheme.error)
    if (notSent.canRetry) {
        Button(onClick = onRetry, enabled = !retrying) {
            if (retrying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Try again")
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
    // Only while the password field is shown: rule 8's protections of a screen that takes it.
    val captureBlocked = protectFromScreenCapture()
    val unlock = state.unlock
    if (unlock.biometrics) {
        FilledTonalButton(
            onClick = onSignWithBiometrics,
            enabled = state.canSignWithBiometrics,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign with fingerprint or face")
        }
        Note("Or with the wallet password:")
    }
    OutlinedTextField(
        value = unlock.password.text,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth().testTag(SEND_PASSWORD_TAG),
        enabled = !state.signing,
        label = { Text("Wallet password") },
        supportingText = unlock.error?.let { error -> { Text(error) } },
        isError = unlock.error != null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSign() }),
    )
    Button(
        onClick = onSign,
        enabled = state.canSign,
        modifier = Modifier.fillMaxWidth().testTag(SEND_SIGN_TAG),
    ) {
        if (state.signing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Signing and sending…")
        } else {
            Text("Sign and send")
        }
    }
    if (!captureBlocked) {
        Text(
            "Screenshots and screen recordings of this window cannot be blocked here. Make sure nothing is " +
                "recording your screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FromLine(from: SendFrom) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(from.address, size = 32.dp)
        Column {
            Text(from.label)
            Text(
                shortAddress(from.address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
