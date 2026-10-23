package eth.serotine.ui.accountdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import eth.serotine.domain.model.AccountKind
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.activityItems
import eth.serotine.ui.components.AccountAvatar
import eth.serotine.ui.components.ActionDropdown
import eth.serotine.ui.components.AddressLine
import eth.serotine.ui.components.Blockie
import eth.serotine.ui.components.ControlBadge
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GhostCircle
import eth.serotine.ui.components.GhostLine
import eth.serotine.ui.components.GhostRows
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.MenuAction
import eth.serotine.ui.components.NetworkMark
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.components.TokenIcon
import eth.serotine.ui.components.Unavailable
import eth.serotine.ui.components.UnavailableDialog
import eth.serotine.ui.components.UnavailableFix
import eth.serotine.ui.components.UnavailableNotice
import eth.serotine.ui.components.UnderLightClientStrip
import eth.serotine.ui.components.WithActionMenu
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.unavailable
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.platform.rememberCopyText
import kotlinx.coroutines.launch

/** Test tag of the account's total, for UI tests. */
const val ACCOUNT_TOTAL_TAG = "account-total"

/** Test tag of the account's full address, for UI tests. */
const val ACCOUNT_ADDRESS_TAG = "account-address"

/** Test tag of a Safe's owners, for UI tests. */
const val ACCOUNT_OWNERS_TAG = "account-owners"

/** Test tag of each asset row, for UI tests. */
const val ACCOUNT_ASSET_TAG = "account-asset"

/** Test tag of the Swap button, for UI tests. */
const val ACCOUNT_SWAP_TAG = "account-swap"

/** Test tag of each queued Safe transaction, for UI tests. */
const val ACCOUNT_QUEUE_ROW_TAG = "account-queue-row"

/**
 * Binds [AccountDetailScreen] to its ViewModel; a row of its Activity opens its detail
 * ([onOpenActivity]). An account that leaves the wallet while the screen is shown (removed from
 * here or elsewhere) ends the screen through [onGone] — once the screen is the one in front:
 * under the remove dialog it waits, so the dialog can say how the removal went before both
 * close. Send is [onSend] from an account and [onPropose] from a Safe, whose transfers are Safe
 * transactions its owners sign; Swap likewise [onSwap] and [onSafeSwap].
 */
@Composable
fun AccountDetailRoute(
    viewModel: AccountDetailViewModel,
    lightClient: LightClientViewModel,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onSend: (contract: String?) -> Unit,
    onPropose: (contract: String?) -> Unit,
    onSwap: (contract: String?) -> Unit,
    onSafeSwap: (contract: String?) -> Unit,
    onReceive: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onOpenSafeTx: (hash: String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
    onAddOnNetwork: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val strip by lightClient.item.collectAsStateWithLifecycle()
    val gone by rememberUpdatedState(onGone)
    // A dialog destination over this one holds it at STARTED; it is RESUMED when in front.
    val inFront = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    val leave = state is AccountDetailUiState.Gone && inFront
    LaunchedEffect(leave) {
        if (leave) gone()
    }
    val isSafe = (state as? AccountDetailUiState.Ready)?.kind == AccountKind.SAFE
    AccountDetailScreen(
        state = state,
        lightClient = strip,
        onBack = onBack,
        onSend = { contract -> if (isSafe) onPropose(contract) else onSend(contract) },
        onSwap = { contract -> if (isSafe) onSafeSwap(contract) else onSwap(contract) },
        onReceive = onReceive,
        onCopy = rememberCopyText(),
        onRename = onRename,
        onRemove = onRemove,
        onHideToken = viewModel::hideToken,
        onMessageShown = viewModel::messageShown,
        onSort = viewModel::sortBy,
        onSegment = viewModel::show,
        onOpenSafeTx = onOpenSafeTx,
        onImportRequest = onImportRequest,
        onOpenActivity = onOpenActivity,
        modifier = modifier,
        onTurnOnNetwork = viewModel::turnOnNetwork,
        onAddOnNetwork = onAddOnNetwork,
    )
}

/**
 * One account (docs/wallet-ux.md, screens 4 and 16) as a pure function of [state]: its blockie,
 * balance, address and control, Send (where it can send), Swap and Receive as buttons, and Copy,
 * Rename and Remove in the top bar's menu, so none of them depends on the long press of the
 * account list; while the asset rows are shown, the menu also sorts them the other way
 * ([onSort]). Under the buttons are its tabs, Assets | Activity and a Safe's Owners | Queue.
 * [onSend] opens Send with the token at a contract chosen, or the network's currency (null). The
 * Activity tab's rows open their detail through [onOpenActivity]. A Safe's Queue opens a
 * transaction through [onOpenSafeTx] by its hash, and Import a request through [onImportRequest].
 * The account's network is named beside its control (docs/networks.md, N3 and N4); while it is off,
 * Send and Swap say so and offer to turn it on through [onTurnOnNetwork] (N19). An externally owned
 * account's menu adds it on another network through [onAddOnNetwork] (N5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    state: AccountDetailUiState,
    lightClient: LightClientItem,
    onBack: () -> Unit,
    onSend: (contract: String?) -> Unit,
    onSwap: (contract: String?) -> Unit,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onHideToken: (contract: String, symbol: String) -> Unit,
    onMessageShown: () -> Unit,
    onSort: (AssetSort) -> Unit,
    onSegment: (DetailSegment) -> Unit,
    onOpenSafeTx: (hash: String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
    modifier: Modifier = Modifier,
    onTurnOnNetwork: () -> Unit = {},
    onAddOnNetwork: () -> Unit = {},
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
    // Send greyed out where it cannot be done: a tap says why (owner ruling, 2026-10-18, #55).
    var unavailable by remember { mutableStateOf<UnavailableNotice?>(null) }
    unavailable?.let { notice -> UnavailableDialog(notice, onDismiss = { unavailable = null }) }
    // On a network that is off, the way to an action is to turn the network on (N19).
    val notice: (String, String) -> UnavailableNotice = { action, reason ->
        val ready = state as? AccountDetailUiState.Ready
        if (ready != null && !ready.networkOn) {
            val network = ready.network.name
            UnavailableNotice(
                "$action needs $network",
                reason,
                listOf(UnavailableFix("Turn on $network", onTurnOnNetwork)),
            )
        } else {
            UnavailableNotice(action, reason)
        }
    }
    val send: (String?) -> Unit = { contract ->
        val reason = (state as? AccountDetailUiState.Ready)?.sendReason
        if (reason == null) onSend(contract) else unavailable = notice("Send", reason)
    }
    val swap: (String?) -> Unit = { contract ->
        val reason = (state as? AccountDetailUiState.Ready)?.swapReason
        if (reason == null) onSwap(contract) else unavailable = notice("Swap", reason)
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
                        // The order of the rows, offered while they are shown: the other order than the current one.
                        val sort = if (!state.showsAssets) {
                            null
                        } else if (state.sort == AssetSort.VALUE) {
                            MenuAction("Sort by name", SerotineIcons.Sort) { onSort(AssetSort.NAME) }
                        } else {
                            MenuAction("Sort by value", SerotineIcons.Sort) { onSort(AssetSort.VALUE) }
                        }
                        ActionDropdown(
                            actions = listOfNotNull(
                                sort,
                                MenuAction("Copy address", SerotineIcons.ContentCopy) { copy(state.address) },
                                MenuAction("Rename", Icons.Filled.Edit, onClick = onRename),
                                // An EOA's address is the same account on every chain (N5); from a
                                // network that is off, greyed out with Turn on (N18).
                                MenuAction(
                                    "Add on another network…",
                                    Icons.Filled.Add,
                                    unavailable = !state.networkOn,
                                ) {
                                    if (state.networkOn) {
                                        onAddOnNetwork()
                                    } else {
                                        val network = state.network.name
                                        unavailable = UnavailableNotice(
                                            "Add on another network",
                                            "$network is off in this wallet. Turned on, ${state.label} can be " +
                                                "added on another network from here.",
                                            listOf(UnavailableFix("Turn on $network", onTurnOnNetwork)),
                                        )
                                    }
                                }.takeIf { state.kind == AccountKind.EOA },
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
        UnderLightClientStrip(lightClient, Modifier.padding(padding)) {
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
                    send,
                    swap,
                    onReceive,
                    copy,
                    onCopyContract = { contract ->
                        onCopy(contract)
                        scope.launch { snackbar.showSnackbar("Contract address copied") }
                    },
                    onHideToken = { asset -> asset.contract?.let { onHideToken(it, asset.symbol) } },
                    onSegment = onSegment,
                    onOpenSafeTx = onOpenSafeTx,
                    onImportRequest = onImportRequest,
                    onOpenActivity = onOpenActivity,
                )
            }
        }
    }
}

/**
 * The account below the top bar, as one list: its header, buttons and tabs, then the tab shown.
 * Lazy, so that a long Activity is composed as it scrolls into view.
 */
@Composable
private fun Details(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onReceive: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
    onSegment: (DetailSegment) -> Unit,
    onOpenSafeTx: (String) -> Unit,
    onImportRequest: () -> Unit,
    onOpenActivity: (ActivityKey) -> Unit,
) {
    // Each item ends with the space before the next, but an Activity's rows, which follow one another.
    val spaced = Modifier.padding(bottom = 16.dp)
    // Back from a row's detail, the restored position waits for the rows it points into: measured
    // against the spinner of an Activity read again, it would be cut back to the top for good.
    val position = rememberLazyListState()
    val whileReading = remember { LazyListState() }
    val reading = state.segment == DetailSegment.ACTIVITY && state.activity == ActivityUiState.Loading
    LazyColumn(
        Modifier.fillMaxSize(),
        state = if (reading) whileReading else position,
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(
                spaced,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(state.address, size = 56.dp, threshold = state.threshold)
                Column {
                    Text(
                        state.total,
                        Modifier.testTag(ACCOUNT_TOTAL_TAG),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    // Right under the total it qualifies: testnet prices, what it leaves out.
                    state.totalNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The account's network beside its control (N3, N4): the same address on
                        // another network is another account.
                        NetworkMark(state.network, style = MaterialTheme.typography.labelMedium)
                        ControlBadge(state.control)
                        state.kindNote?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                    if (!state.networkOn) {
                        Text(
                            "${state.network.name} is off in this wallet: nothing is checked.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    // Why the title is the address: the ENS name it was named by no longer points here.
                    state.nameNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        // In full, as docs/wallet-ux.md's screen 4 has it, with Copy beside it.
        item {
            Row(
                spaced,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        }
        item { Actions(state, onSend, onSwap, onReceive, spaced) }
        item { SegmentChoice(state.segments, state.segment, state.queue, onSegment, spaced) }
        // The same predicate as the top bar's sort entry, so the menu sorts exactly when the rows are shown.
        when {
            state.showsAssets -> item { Assets(state, onSend, onSwap, onCopyContract, onHideToken) }
            state.segment == DetailSegment.ACTIVITY -> activityItems(state.activity, onOpenActivity)
            state.segment == DetailSegment.OWNERS -> item { SafeOwners(checkNotNull(state.safe)) }
            else -> item { SafeQueueList(checkNotNull(state.queue), onOpenSafeTx, onImportRequest) }
        }
    }
}

/**
 * Send, Swap and Receive in one line, sharing its width: three equal buttons fit a phone as
 * narrow as an iPhone mini's 375 points, where wrapping would push Receive onto a line of its own.
 * Where a label and its icon no longer fit a third of the line, measured in the device's own font
 * and text size (iOS's system font is wider than Android's; the phone's text size setting, iOS's
 * Dynamic Type, makes it wider still), they leave out their icons, so that each still says what
 * it does in full; only past that does a label end in an ellipsis.
 */
@Composable
private fun Actions(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        // The widest label as the buttons draw it (Material's buttons set their labels in labelLarge).
        val widest = remember(measurer, style, density) {
            with(density) { ActionLabels.maxOf { measurer.measure(it, style).size.width }.toDp() }
        }
        val third = (maxWidth - ActionGap * 2) / 3 - ActionPadding * 2
        // A dp of slack: the layout rounds each part to whole pixels, and a label a pixel short ends
        // in an ellipsis, while icons left out a little early are not missed.
        val icons = third >= ActionIcon + ActionIconGap + widest + 1.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ActionGap)) {
            ActionButton(
                SEND_LABEL,
                Icons.AutoMirrored.Filled.Send.takeIf { icons },
                onClick = { onSend(null) },
                Modifier.weight(1f).unavailable(!state.canSend),
                available = state.canSend,
            )
            ActionButton(
                SWAP_LABEL,
                SerotineIcons.Swap.takeIf { icons },
                onClick = { onSwap(null) },
                Modifier.weight(1f).testTag(ACCOUNT_SWAP_TAG).unavailable(!state.canSwap),
                available = state.canSwap,
            )
            ActionButton(RECEIVE_LABEL, SerotineIcons.QrCode.takeIf { icons }, onClick = onReceive, Modifier.weight(1f))
        }
    }
}

/** [Actions]' labels: what its buttons draw, and what is measured for whether their icons fit. */
private const val SEND_LABEL = "Send"
private const val SWAP_LABEL = "Swap"
private const val RECEIVE_LABEL = "Receive"
private val ActionLabels = listOf(SEND_LABEL, SWAP_LABEL, RECEIVE_LABEL)

/** The space between [Actions]' buttons. */
private val ActionGap = 8.dp

/** Each [ActionButton]'s padding on either side, less than a button's own. */
private val ActionPadding = 8.dp

/** The size of an [ActionButton]'s icon, and the space between it and its label. */
private val ActionIcon = 18.dp
private val ActionIconGap = 6.dp

/**
 * One of [Actions]' buttons: its [icon] (none: left out) and [label] on one line, with less side
 * padding than a button's own so that three share a phone's width; greyed out unless
 * [available], a tap then saying why (owner ruling, 2026-10-18, #55).
 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    available: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (available) ButtonDefaults.filledTonalButtonColors() else Unavailable.buttonColors(),
        contentPadding = PaddingValues(horizontal = ActionPadding, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(ActionIcon))
            Spacer(Modifier.size(ActionIconGap))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The asset rows (docs/wallet-ux.md, screen 4), each with the block its amount was verified
 * at, a ghost row per token not read yet, and what is not known yet about the other tokens. A
 * tap on a row opens Send with its asset chosen, a long press its menu.
 */
@Composable
private fun Assets(
    state: AccountDetailUiState.Ready,
    onSend: (String?) -> Unit,
    onSwap: (String?) -> Unit,
    onCopyContract: (String) -> Unit,
    onHideToken: (AssetRow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.assets.forEach { asset ->
            val contract = asset.contract
            // Long press (right click): docs/wallet-ux.md, screen 5. Each action is also behind a
            // visible button: Send and Swap above (the asset chosen there), the others in Settings → Tokens.
            val actions = listOfNotNull(
                MenuAction("Send ${asset.symbol}", Icons.AutoMirrored.Filled.Send, unavailable = !state.canSend) {
                    onSend(contract)
                },
                MenuAction("Swap ${asset.symbol}", SerotineIcons.Swap, unavailable = !state.canSwap) {
                    onSwap(contract)
                },
                contract?.let { MenuAction("Copy contract", SerotineIcons.ContentCopy) { onCopyContract(it) } },
                contract?.let { MenuAction("Hide token", SerotineIcons.Visibility) { onHideToken(asset) } },
            )
            // A tap sends the asset. The row stays as it is where Send cannot be done (its balance is
            // still to be read, and its menu still works): the tap then says why, as the greyed-out
            // Send button does, and accessibility services announce it as such.
            WithActionMenu(
                actions = actions,
                menuLabel = "Actions for ${asset.symbol}",
                onClick = { onSend(contract) },
                clickLabel = if (state.canSend) "Send ${asset.symbol}" else "Why Send ${asset.symbol} is unavailable",
            ) {
                AssetItem(asset)
            }
        }
        // A row's place for each token not read yet: one that holds some takes a place, one that holds
        // none gives one up at the bottom, so the list grows no longer as the tokens are read.
        if (state.pendingAssets > 0) {
            GhostRows(state.pendingAssets, "Tokens not read yet", spacing = 4.dp) {
                AssetGhost()
            }
        }
        state.tokensNote?.let { note ->
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.pricesNote?.let { note ->
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The account's tabs, [segments]: Assets | Activity, and a Safe's Owners | Queue (n)
 * (docs/wallet-ux.md, screens 4 and 16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentChoice(
    segments: List<DetailSegment>,
    segment: DetailSegment,
    queue: SafeQueue?,
    onSegment: (DetailSegment) -> Unit,
    modifier: Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = segment == choice,
                onClick = { onSegment(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                // Four tabs share a phone's width: no check mark beside the chosen one (its fill says it),
                // and less side padding, so that "Queue (12)" stays on one line.
                contentPadding = PaddingValues(horizontal = 8.dp),
                icon = {},
                label = {
                    Text(
                        when (choice) {
                            DetailSegment.ASSETS -> "Assets"
                            DetailSegment.ACTIVITY -> "Activity"
                            DetailSegment.OWNERS -> "Owners"
                            DetailSegment.QUEUE -> (queue as? SafeQueue.Shown)?.let { "Queue (${it.rows.size})" }
                                ?: "Queue"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/**
 * A Safe's transactions waiting for its owners on this device, in nonce order (docs/wallet-ux.md,
 * screen 16): each with what it does, its nonce, its signatures and why this wallet will not sign
 * it, if it will not; a tap opens it. Import a request brings in one from another device.
 */
@Composable
private fun SafeQueueList(queue: SafeQueue, onOpenSafeTx: (String) -> Unit, onImportRequest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImportRequest) {
            Icon(SerotineIcons.QrCode, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Import a request")
        }
        when (queue) {
            SafeQueue.Checking ->
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading this device's queue", spacing = 8.dp) { QueueGhost() }
            is SafeQueue.Unavailable -> Text(queue.message, color = MaterialTheme.colorScheme.error)
            is SafeQueue.Shown -> {
                if (queue.rows.isEmpty()) {
                    Text(
                        "Nothing queued on this device. Send proposes a transaction; Import a request brings in " +
                            "one another owner shared.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                queue.rows.forEach { row -> QueueItem(row) { onOpenSafeTx(row.hash) } }
                queue.note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                Text(
                    "Signatures as this device has them; approvals made on chain, and signatures shared " +
                        "elsewhere, show on each transaction.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A [QueueItem] not read yet, of its height. */
@Composable
private fun QueueGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(160.dp) },
        supportingContent = { GhostLine(220.dp) },
    )
}

@Composable
private fun QueueItem(row: QueueRow, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag(ACCOUNT_QUEUE_ROW_TAG).clickable(onClick = onClick),
        leadingContent = { row.about?.let { Blockie(it, size = 32.dp) } },
        headlineContent = { Text(row.action) },
        supportingContent = {
            Column {
                Text("${row.nonce} · ${row.signatures}")
                listOfNotNull(row.warning, row.refusal).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/**
 * A Safe's owners with their blockies, "this device" on the ones whose keys are held here, and
 * the threshold; the modules and the guard follow them (docs/wallet-ux.md, screen 16).
 */
@Composable
private fun SafeOwners(safe: SafeDetails) {
    Column(Modifier.testTag(ACCOUNT_OWNERS_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (safe) {
            SafeDetails.Checking -> {
                // The title and the threshold, then owners: as many as a Safe's are, until they are read.
                GhostLine(160.dp, style = MaterialTheme.typography.titleSmall)
                GhostLine(200.dp)
                GhostRows(GHOST_ROWS_UNKNOWN, "Reading the Safe's storage", spacing = 8.dp) { OwnerGhost() }
            }
            is SafeDetails.Unavailable -> Text(safe.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SafeDetails.Shown -> {
                Text(safe.title, style = MaterialTheme.typography.titleSmall)
                Text(safe.signers)
                safe.owners.forEach { owner ->
                    ListItem(
                        leadingContent = { AccountAvatar(owner.address, size = 32.dp) },
                        headlineContent = { Text(owner.label ?: shortAddress(owner.address)) },
                        supportingContent = {
                            Text(
                                if (owner.label != null) shortAddress(owner.address) else "Not in this wallet",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        trailingContent = {
                            if (owner.heldHere) Text("This device", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
                if (safe.modules.isNotEmpty()) {
                    Text(
                        "Modules: each can move this Safe's funds without the owners' signatures.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    safe.modules.forEach { AddressLine(it) }
                }
                safe.guard?.let { AddressLine(it, "A guard checks every transaction the owners sign:") }
                safe.moduleGuard?.let { AddressLine(it, "A module guard checks the modules' transactions:") }
                Text(
                    safe.checkedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An owner's row of [SafeOwners] not read yet, of its height. */
@Composable
private fun OwnerGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(96.dp) },
        supportingContent = { GhostLine(120.dp) },
    )
}

/** An [AssetItem] whose balance is still being checked, of its height. */
@Composable
private fun AssetGhost() {
    ListItem(
        leadingContent = { GhostCircle(32.dp) },
        headlineContent = { GhostLine(56.dp) },
        supportingContent = { GhostLine(180.dp) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                GhostLine(72.dp, style = MaterialTheme.typography.bodyLarge)
                GhostLine(48.dp, style = MaterialTheme.typography.labelMedium)
            }
        },
    )
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
                asset.lastKnown?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                asset.warning?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    asset.amount,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (asset.verified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                asset.value?.let {
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
