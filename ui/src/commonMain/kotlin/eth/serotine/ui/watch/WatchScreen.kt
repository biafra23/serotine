package eth.serotine.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.AddressLine
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.shortAddress

/** Test tag of the address field, for UI tests. */
const val WATCH_ADDRESS_TAG = "watch-address"

/** Test tag of the name field, for UI tests. */
const val WATCH_NAME_TAG = "watch-name"

/** Test tag of the detection card, for UI tests. */
const val WATCH_DETECTION_TAG = "watch-detection"

/** Test tag of the checksummed address on the detection card, for UI tests. */
const val WATCH_CHECKSUMMED_TAG = "watch-checksummed"

/** What the address field says while nothing else is to be said of it. */
private const val ADDRESS_HINT = "0x and 40 hexadecimal digits, or an ENS name like name.eth"

/** Binds [WatchScreen] to its ViewModel; an added address ends the screen through [onAdded]. */
@Composable
fun WatchRoute(viewModel: WatchViewModel, onBack: () -> Unit, onAdded: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val added by rememberUpdatedState(onAdded)
    LaunchedEffect(state.done) {
        if (state.done) added()
    }
    WatchScreen(
        state,
        viewModel::onAddressChange,
        viewModel::onNameChange,
        viewModel::add,
        onBack,
        modifier,
        onRetryLookup = viewModel::onRetryLookup,
    )
}

/**
 * Watch address (docs/wallet-ux.md, screen 10) as a pure function of [state]: the address or
 * ENS name and a name, the detection card built from verified code, and Add, enabled only for
 * a verified account. [onRetryLookup] resolves a name again that could not be verified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    state: WatchUiState,
    onAddressChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLookup: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Watch address") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth().testTag(WATCH_ADDRESS_TAG),
                enabled = !state.adding,
                label = { Text("Address or ENS name") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                isError = state.addressError != null,
                supportingText = { Text(state.addressError ?: state.addressNote ?: ADDRESS_HINT) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            if (state.canRetryLookup) TextButton(onClick = onRetryLookup) { Text("Try again") }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth().testTag(WATCH_NAME_TAG),
                enabled = !state.adding,
                label = { Text("Name (optional)") },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { error -> { Text(error) } },
            )
            state.detection?.let { DetectionCard(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onAdd, enabled = state.canAdd, modifier = Modifier.align(Alignment.End)) { Text("Add") }
        }
    }
}

@Composable
private fun DetectionCard(detection: Detection) {
    Surface(
        Modifier.fillMaxWidth().testTag(WATCH_DETECTION_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (detection) {
                Detection.Checking -> Text("Checking the address on chain…")
                is Detection.Account -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccountAvatar(detection.address, size = 36.dp)
                        Column {
                            Text(if (detection.delegate == null) "Account" else "Account with a delegation")
                            Text(
                                detection.checkedAt,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // In full and checksummed: this is the address the user confirms.
                    Text(
                        detection.address,
                        Modifier.testTag(WATCH_CHECKSUMMED_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (detection.delegate == null) {
                        Text(
                            "No contract code: an account a key controls (or a contract not deployed yet).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    detection.delegate?.let { delegate ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Blockie(delegate, size = 20.dp)
                            Text(
                                "Its code delegates to ${shortAddress(delegate)} (EIP-7702), which can act for it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlBadge(Control.WATCH_ONLY)
                        Text("Detected from verified code", style = MaterialTheme.typography.labelMedium)
                    }
                }
                is Detection.Safe -> SafeCard(detection)
                is Detection.Contract -> Text(detection.message)
                is Detection.InWallet -> Text("Already in your wallet as “${detection.label}”.")
                is Detection.Unavailable -> Text(detection.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * What the light client proved of a Safe (docs/wallet-ux.md, screen 10): its version, who must
 * sign, the owners' blockies, and the modules and guard, which act without them or on every
 * transaction.
 */
@Composable
private fun SafeCard(safe: Detection.Safe) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(safe.address, size = 36.dp, threshold = safe.threshold)
        Column {
            Text("${safe.title} · ${safe.signers}")
            Text(
                safe.checkedAt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        safe.address,
        Modifier.testTag(WATCH_CHECKSUMMED_TAG),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
    Text("Owners", style = MaterialTheme.typography.labelMedium)
    safe.owners.forEach { owner ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Blockie(owner, size = 20.dp)
            Text(shortAddress(owner), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    if (safe.modules.isNotEmpty()) {
        Text(
            if (safe.modules.size == 1) {
                "1 module is enabled: it can move this Safe's funds without the owners' signatures."
            } else {
                "${safe.modules.size} modules are enabled: each can move this Safe's funds without the " +
                    "owners' signatures."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        safe.modules.forEach { module -> AddressLine(module) }
    }
    safe.guard?.let { guard -> AddressLine(guard, "A guard checks every transaction the owners sign:") }
    safe.moduleGuard?.let { guard -> AddressLine(guard, "A module guard checks the modules' transactions:") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ControlBadge(safe.control)
        Text("Detected from verified code and storage", style = MaterialTheme.typography.labelMedium)
    }
}
