package eth.serotine.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.ui.accountdetail.AccountDetailRoute
import eth.serotine.ui.activity.ActivityRoute
import eth.serotine.ui.addtoken.AddTokenRoute
import eth.serotine.ui.assets.AccountAction
import eth.serotine.ui.assets.AssetsRoute
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.createaccount.CreateAccountRoute
import eth.serotine.ui.createsafe.CreateSafeRoute
import eth.serotine.ui.currency.CurrencyRoute
import eth.serotine.ui.lightclient.LightClientViewModel
import eth.serotine.ui.mnemonicimport.MnemonicImportRoute
import eth.serotine.ui.platform.ScreenCaptureWarnings
import eth.serotine.ui.receive.ReceiveRoute
import eth.serotine.ui.removeaccount.RemoveAccountRoute
import eth.serotine.ui.renameaccount.RenameAccountRoute
import eth.serotine.ui.safeimport.SafeImportRoute
import eth.serotine.ui.safepropose.SafeProposeRoute
import eth.serotine.ui.safeswap.SafeSwapRoute
import eth.serotine.ui.safetx.SafeTxRoute
import eth.serotine.ui.security.SecurityRoute
import eth.serotine.ui.send.SendRoute
import eth.serotine.ui.settings.SettingsRoute
import eth.serotine.ui.swap.SwapRoute
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.tokens.TokensRoute
import eth.serotine.ui.transferstatus.TransferStatusRoute
import eth.serotine.ui.update.UpdateRequiredRoute
import eth.serotine.ui.update.UpdateRequiredUiState
import eth.serotine.ui.update.UpdateRequiredViewModel
import eth.serotine.ui.watch.WatchRoute

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters.
 *
 * Two tabs, Assets and Settings, under a bottom bar (a rail on a wide window), around the
 * one [NavHost] (docs/architecture.md). Each tab is a nested graph with its own back stack;
 * a flow that ends in a key operation covers the bar. While a network upgrade has left this
 * version unable to follow the network, it shows the update screen and nothing else.
 */
@Composable
fun App(graph: AppGraph) = App(graph, rememberNavController())

/** [App] with the [navController] a test drives. */
@Composable
internal fun App(graph: AppGraph, navController: NavHostController) {
    SerotineTheme {
        // Scoped to the host, not to a destination: it decides which destinations there are.
        val update = viewModel { graph.updateRequiredViewModel() }
        // One strip for every screen that draws it, followed while one of them is shown.
        val lightClient = viewModel { graph.lightClientViewModel() }
        val entry by navController.currentBackStackEntryAsState()
        val selected = entry?.destination?.hierarchy?.firstNotNullOfOrNull { destination ->
            Tab.entries.firstOrNull { it.graph == destination.route }
        }
        val select: (Tab) -> Unit = { tab -> navController.select(tab, current = selected) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_WINDOW
            // One layout for both widths, so the NavHost stays where it is in the composition
            // when the window crosses WIDE_WINDOW: only the bar or the rail comes and goes.
            Row(Modifier.fillMaxSize()) {
                if (wide && selected != null) {
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == selected,
                                onClick = { select(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    // The screens' own scaffolds take the system insets; this one only makes
                    // room for the bar, which takes the navigation bar's.
                    contentWindowInsets = WindowInsets(0.dp),
                    bottomBar = {
                        if (!wide && selected != null) {
                            NavigationBar {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = tab == selected,
                                        onClick = { select(tab) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Destinations(
                        graph,
                        navController,
                        update,
                        lightClient,
                        Modifier.padding(padding).consumeWindowInsets(padding),
                    )
                }
            }
        }
        ScreenCaptureWarnings()
    }
}

/** The tabs of the bottom bar: each the [graph] of its screens, entered at [start]. */
private enum class Tab(val label: String, val icon: ImageVector, val graph: String, val start: String) {
    ASSETS("Assets", SerotineIcons.Wallet, "assets-tab", Destination.ASSETS),
    SETTINGS("Settings", Icons.Filled.Settings, "settings-tab", Destination.SETTINGS),
}

/**
 * Shows [tab]. The current one again returns to its start. Settings opens over the Assets
 * tab's stack, which stays as the user left it, and Assets closes Settings back down to it
 * — as the back key does. No stack is saved aside, so none can come back after the user has
 * left it by another way (saveState/restoreState would bring back an account screen left by
 * the back key from Settings).
 */
private fun NavController.select(tab: Tab, current: Tab?) {
    when {
        tab == current -> popBackStack(tab.start, inclusive = false)
        tab == Tab.ASSETS -> popBackStack(Tab.SETTINGS.graph, inclusive = true)
        else -> navigate(tab.graph) { launchSingleTop = true }
    }
}

/**
 * The one [NavHost]. While [update] says an update is required, its screen replaces every
 * other: the one navigation no screen asks for (docs/architecture.md).
 */
@Composable
private fun Destinations(
    graph: AppGraph,
    navController: NavHostController,
    update: UpdateRequiredViewModel,
    lightClient: LightClientViewModel,
    modifier: Modifier = Modifier,
) {
    val updateState by update.uiState.collectAsStateWithLifecycle()
    val updateRequired = updateState is UpdateRequiredUiState.Required
    // In the NavHost's own composition, so the graph it navigates is set by the time this runs.
    LaunchedEffect(updateRequired) {
        if (updateRequired) {
            // The whole back stack goes: every other screen's ViewModel is cleared, the seed
            // screen's words with it, and back leaves the app instead of reaching a screen.
            navController.navigate(Destination.UPDATE_REQUIRED) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        } else if (navController.currentDestination?.route == Destination.UPDATE_REQUIRED) {
            navController.navigate(Tab.ASSETS.graph) {
                popUpTo(Destination.UPDATE_REQUIRED) { inclusive = true }
            }
        }
    }
    // One destination per screen. Inside a destination, viewModel {} is scoped to its
    // back-stack entry: the ViewModel survives configuration changes while the screen is
    // on the back stack, and is cleared when the screen is popped off it.
    NavHost(navController, startDestination = Tab.ASSETS.graph, modifier = modifier) {
        navigation(startDestination = Destination.ASSETS, route = Tab.ASSETS.graph) {
            composable(Destination.ASSETS) {
                AssetsRoute(
                    viewModel = viewModel { graph.assetsViewModel() },
                    onWatchAddress = { navController.navigate(Destination.WATCH) { launchSingleTop = true } },
                    onImportPhrase = { navController.navigate(Destination.MNEMONIC_IMPORT) { launchSingleTop = true } },
                    onCreateAccount = { navController.navigate(Destination.CREATE_ACCOUNT) { launchSingleTop = true } },
                    onCreateSafe = { navController.navigate(Destination.CREATE_SAFE) { launchSingleTop = true } },
                    onAccountAction = { action -> navController.open(action) },
                )
            }
            composable(Destination.WATCH) {
                WatchRoute(
                    viewModel = viewModel { graph.watchViewModel() },
                    lightClient = lightClient,
                    onBack = { navController.leave(Destination.WATCH) },
                    onAdded = { navController.leave(Destination.WATCH) },
                )
            }
            withAddress(Destination.ACCOUNT, navController) { address ->
                AccountDetailRoute(
                    viewModel = viewModel { graph.accountDetailViewModel(address) },
                    lightClient = lightClient,
                    onBack = { navController.leave(Destination.ACCOUNT) },
                    onGone = { navController.leave(Destination.ACCOUNT) },
                    onSend = { contract -> navController.send(address.hex, contract) },
                    onPropose = { contract -> navController.propose(address.hex, contract) },
                    onSwap = { contract -> navController.swap(address.hex, contract) },
                    onSafeSwap = { contract -> navController.safeSwap(address, contract) },
                    onReceive = { navController.open(AccountAction.Receive(address.hex)) },
                    onActivity = { navController.openActivity(address) },
                    onRename = { navController.open(AccountAction.Rename(address.hex)) },
                    onRemove = { navController.open(AccountAction.Remove(address.hex)) },
                    onOpenSafeTx = { hash -> navController.openSafeTx(address, hash) },
                    onImportRequest = { navController.importRequest(address) },
                )
            }
            withAddress(Destination.ACTIVITY, navController) { address ->
                ActivityRoute(
                    viewModel = viewModel { graph.activityViewModel(address) },
                    lightClient = lightClient,
                    onBack = { navController.leave(Destination.ACTIVITY) },
                    onOpen = { hash -> navController.openTransfer(hash) },
                )
            }
            withAddress(Destination.RECEIVE, navController) { address ->
                ReceiveRoute(
                    viewModel = viewModel { graph.receiveViewModel(address) },
                    onBack = { navController.leave(Destination.RECEIVE) },
                    onGone = { navController.leave(Destination.RECEIVE) },
                )
            }
            // In the Assets graph, so the bar stays while they are open over its screens.
            dialogWithAddress(Destination.RENAME, navController) { address ->
                RenameAccountRoute(
                    viewModel = viewModel { graph.renameAccountViewModel(address) },
                    onClose = { navController.leave(Destination.RENAME) },
                )
            }
            dialogWithAddress(Destination.REMOVE, navController) { address ->
                RemoveAccountRoute(
                    viewModel = viewModel { graph.removeAccountViewModel(address) },
                    onClose = { navController.leave(Destination.REMOVE) },
                )
            }
        }
        navigation(startDestination = Destination.SETTINGS, route = Tab.SETTINGS.graph) {
            composable(Destination.SETTINGS) {
                SettingsRoute(
                    viewModel { graph.settingsViewModel() },
                    onCurrency = { navController.navigate(Destination.CURRENCY) { launchSingleTop = true } },
                    onTokens = { navController.navigate(Destination.TOKENS) { launchSingleTop = true } },
                    onSecurity = { navController.navigate(Destination.SECURITY) { launchSingleTop = true } },
                )
            }
            composable(Destination.SECURITY) {
                SecurityRoute(
                    viewModel { graph.securityViewModel() },
                    onBack = { navController.leave(Destination.SECURITY) },
                )
            }
            composable(Destination.CURRENCY) {
                CurrencyRoute(
                    viewModel { graph.currencyViewModel() },
                    onBack = { navController.leave(Destination.CURRENCY) },
                    onChosen = { navController.leave(Destination.CURRENCY) },
                )
            }
            composable(Destination.TOKENS) {
                TokensRoute(
                    viewModel { graph.tokensViewModel() },
                    lightClient,
                    onBack = { navController.leave(Destination.TOKENS) },
                    onAddToken = { navController.navigate(Destination.ADD_TOKEN) { launchSingleTop = true } },
                )
            }
            composable(Destination.ADD_TOKEN) {
                AddTokenRoute(
                    viewModel { graph.addTokenViewModel() },
                    lightClient,
                    onBack = { navController.leave(Destination.ADD_TOKEN) },
                    onAdded = { navController.leave(Destination.ADD_TOKEN) },
                )
            }
        }
        // Outside the tabs: a key operation covers the bar until it ends.
        composable(Destination.CREATE_ACCOUNT) {
            CreateAccountRoute(
                viewModel = viewModel { graph.createAccountViewModel() },
                onBack = { navController.leave(Destination.CREATE_ACCOUNT) },
                onCreated = { navController.leave(Destination.CREATE_ACCOUNT) },
            )
        }
        // A Safe's creation ends in its deployment: then where that stands, and back from it to Assets.
        composable(Destination.CREATE_SAFE) {
            CreateSafeRoute(
                viewModel = viewModel { graph.createSafeViewModel() },
                lightClient = lightClient,
                onBack = { navController.leave(Destination.CREATE_SAFE) },
                onSent = { hash ->
                    navController.navigate("${Destination.TRANSFER}/$hash") {
                        popUpTo(Destination.CREATE_SAFE) { inclusive = true }
                    }
                },
            )
        }
        composable(Destination.MNEMONIC_IMPORT) {
            MnemonicImportRoute(
                viewModel = viewModel { graph.mnemonicImportViewModel() },
                onBack = { navController.leave(Destination.MNEMONIC_IMPORT) },
                onImported = { navController.leave(Destination.MNEMONIC_IMPORT) },
            )
        }
        // Send, and then where the transfer stands: the transaction's flow, over the bar too.
        composable(
            SEND_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            if (address != null && (asked == null || asset != null)) {
                SendRoute(
                    viewModel = viewModel { graph.sendViewModel(address, asset) },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SEND_ROUTE, inclusive = true) },
                    // Back from the status goes where Send was opened from, not to a signed review.
                    onSent = { hash ->
                        navController.navigate("${Destination.TRANSFER}/$hash") {
                            popUpTo(SEND_ROUTE) { inclusive = true }
                        }
                    },
                    // An earlier transaction that may still be on its way: its status, over Send.
                    onOpenTransfer = { hash ->
                        navController.navigate("${Destination.TRANSFER}/$hash") { launchSingleTop = true }
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SEND_ROUTE, inclusive = true) }
            }
        }
        // Swap, and then where the swap stands: the transaction's flow, over the bar too.
        composable(
            SWAP_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            if (address != null && (asked == null || asset != null)) {
                SwapRoute(
                    viewModel = viewModel { graph.swapViewModel(address, asset) },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SWAP_ROUTE, inclusive = true) },
                    // Back from the status goes where Swap was opened from, not to a signed review.
                    onSent = { hash ->
                        navController.navigate("${Destination.TRANSFER}/$hash") {
                            popUpTo(SWAP_ROUTE) { inclusive = true }
                        }
                    },
                    // The approval, an earlier swap, or one that may still be on its way: its status, over Swap.
                    onOpenTransfer = { hash ->
                        navController.navigate("${Destination.TRANSFER}/$hash") { launchSingleTop = true }
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SWAP_ROUTE, inclusive = true) }
            }
        }
        // A Safe's transactions: proposing one, importing one, and one of them; over the bar too,
        // as each ends in a signature or a transaction.
        composable(
            SAFE_PROPOSE_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            if (address != null && (asked == null || asset != null)) {
                SafeProposeRoute(
                    viewModel = viewModel { graph.safeProposeViewModel(address, asset) },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SAFE_PROPOSE_ROUTE, inclusive = true) },
                    // Signed and queued: the transaction's screen, and back from it to where Send was opened.
                    onProposed = { hash -> navController.openSafeTx(address, hash, replacing = SAFE_PROPOSE_ROUTE) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_PROPOSE_ROUTE, inclusive = true) }
            }
        }
        composable(
            SAFE_SWAP_ROUTE,
            arguments = listOf(
                navArgument(ASSET) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(REPLACING) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            val asked = entry.savedStateHandle.get<String>(ASSET)
            val asset = asked?.let(Address::parse)
            val replaced = entry.savedStateHandle.get<String>(REPLACING)
            val replacing = replaced?.lowercase()?.takeIf(TRANSACTION_HASH::matches)?.let(Digest::parse)
            if (address != null && (asked == null || asset != null) && (replaced == null || replacing != null)) {
                SafeSwapRoute(
                    viewModel = viewModel { graph.safeSwapViewModel(address, asset, replacing) },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SAFE_SWAP_ROUTE, inclusive = true) },
                    // Signed and queued: the swap's screen, and back from it to where Swap was opened.
                    onProposed = { hash -> navController.openSafeTx(address, hash, replacing = SAFE_SWAP_ROUTE) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_SWAP_ROUTE, inclusive = true) }
            }
        }
        composable(SAFE_IMPORT_ROUTE) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            if (address != null) {
                SafeImportRoute(
                    viewModel = viewModel { graph.safeImportViewModel(address) },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SAFE_IMPORT_ROUTE, inclusive = true) },
                    // Checked and queued: the transaction's screen, which says where it went.
                    onImported = { hash ->
                        navController.openSafeTx(address, hash, replacing = SAFE_IMPORT_ROUTE, imported = true)
                    },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_IMPORT_ROUTE, inclusive = true) }
            }
        }
        composable(SAFE_TX_ROUTE) { entry ->
            val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
            val hash = entry.savedStateHandle.get<String>(HASH)?.lowercase()?.takeIf(TRANSACTION_HASH::matches)
                ?.let(Digest::parse)
            if (address != null && hash != null) {
                SafeTxRoute(
                    // Said once, by the ViewModel this entry makes first: not again after a restore.
                    viewModel = viewModel {
                        val imported = entry.savedStateHandle.remove<Boolean>(IMPORTED) == true
                        graph.safeTxViewModel(address, hash, imported)
                    },
                    lightClient = lightClient,
                    onBack = { navController.popBackStack(SAFE_TX_ROUTE, inclusive = true) },
                    // Back from the status goes where the transaction was opened from, not to a signed review.
                    onSent = { sent ->
                        navController.navigate("${Destination.TRANSFER}/$sent") {
                            popUpTo(SAFE_TX_ROUTE) { inclusive = true }
                        }
                    },
                    // Over this one: once the new swap is signed, its own screen takes Swap's place.
                    onProposeAgain = { navController.safeSwap(address, asset = null, replacing = hash) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(SAFE_TX_ROUTE, inclusive = true) }
            }
        }
        composable(TRANSFER_ROUTE) { entry ->
            val hash = entry.savedStateHandle.get<String>(HASH)?.lowercase()?.takeIf(TRANSACTION_HASH::matches)
            if (hash != null) {
                TransferStatusRoute(
                    viewModel = viewModel { graph.transferStatusViewModel(hash) },
                    lightClient = lightClient,
                    onDone = { navController.popBackStack(TRANSFER_ROUTE, inclusive = true) },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack(TRANSFER_ROUTE, inclusive = true) }
            }
        }
        // Outside the tabs too, and alone on the back stack while it is shown.
        composable(Destination.UPDATE_REQUIRED) {
            UpdateRequiredRoute(viewModel = update)
        }
    }
}

/** Opens what [action] asks for; Copy is the screen's own, done on the clipboard. */
private fun NavController.open(action: AccountAction) {
    val route = when (action) {
        is AccountAction.Send -> return send(action.address, asset = null)
        is AccountAction.Propose -> return propose(action.address, asset = null)
        is AccountAction.Open -> Destination.ACCOUNT
        is AccountAction.Receive -> Destination.RECEIVE
        is AccountAction.Rename -> Destination.RENAME
        is AccountAction.Remove -> Destination.REMOVE
        is AccountAction.Copy -> return
    }
    // A second tap while the first one's screen is opening: one screen, the first one asked
    // for. (Not launchSingleTop: that would reuse the entry, and its address, for another one.)
    if (currentDestination?.route == "$route/{$ADDRESS}") return
    // The route carries the address in lower case: an identifier, never display text.
    val address = Address.parse(action.address) ?: return
    navigate("$route/${address.hex}")
}

/**
 * Opens Send from the account at [address], with the token at [asset] chosen (null: the
 * network's currency). The route carries both in lower case: identifiers, never display text.
 */
private fun NavController.send(address: String, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SEND_ROUTE) return
    val from = Address.parse(address) ?: return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SEND}/${from.hex}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Swap from the account at [address], selling the token at [asset] (null: the network's
 * currency); both in lower case, as identifiers.
 */
private fun NavController.swap(address: String, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SWAP_ROUTE) return
    val from = Address.parse(address) ?: return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SWAP}/${from.hex}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Send from the Safe at [address] — the proposal of a Safe transaction — with the token at
 * [asset] chosen (null: the network's currency); both in lower case, as identifiers.
 */
private fun NavController.propose(address: String, asset: String?) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SAFE_PROPOSE_ROUTE) return
    val safe = Address.parse(address) ?: return
    val token = asset?.let { Address.parse(it) ?: return }
    navigate("${Destination.SAFE}/${safe.hex}/${Destination.PROPOSE}" + (token?.let { "?$ASSET=${it.hex}" } ?: ""))
}

/**
 * Opens Swap from the Safe at [safe] — the proposal of its Safe transactions — selling the token at
 * [asset] (null: the network's currency; one that is no address opens nothing); or, with
 * [replacing], the swap queued under that hash proposed again in its place.
 */
private fun NavController.safeSwap(safe: Address, asset: String?, replacing: Digest? = null) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == SAFE_SWAP_ROUTE) return
    val token = asset?.let { Address.parse(it) ?: return }
    val query = listOfNotNull(token?.let { "$ASSET=${it.hex}" }, replacing?.let { "$REPLACING=0x${it.hex}" })
        .joinToString("&")
    navigate("${Destination.SAFE}/${safe.hex}/${Destination.SWAP}" + if (query.isEmpty()) "" else "?$query")
}

/** Opens the Activity of the account at [address]: the transactions this device sent for it. */
private fun NavController.openActivity(address: Address) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    if (currentDestination?.route == "${Destination.ACTIVITY}/{$ADDRESS}") return
    navigate("${Destination.ACTIVITY}/${address.hex}")
}

/** Opens the status of the transaction [hash] (`0x` and 64 hex digits), as a route carries it: in lower case. */
private fun NavController.openTransfer(hash: String) {
    // A second tap while the first one's screen is opening: one screen, the first one asked for.
    // (Not launchSingleTop: that would reuse the entry, and its hash, for another one.)
    if (currentDestination?.route == TRANSFER_ROUTE) return
    val route = hash.lowercase().takeIf(TRANSACTION_HASH::matches) ?: return
    navigate("${Destination.TRANSFER}/$route")
}

/** Opens Import a request for the Safe at [safe]. */
private fun NavController.importRequest(safe: Address) {
    if (currentDestination?.route == SAFE_IMPORT_ROUTE) return
    navigate("${Destination.SAFE}/${safe.hex}/${Destination.IMPORT}")
}

/**
 * Opens the Safe transaction [hash] (`0x` and 64 hex digits) of the Safe at [safe]; in place of the
 * destination [replacing] when set (the proposal or import that led to it), so back from it returns
 * to where that was opened from. [imported] when an import has just queued it: the screen says so.
 */
private fun NavController.openSafeTx(
    safe: Address,
    hash: String,
    replacing: String? = null,
    imported: Boolean = false,
) {
    if (replacing == null && currentDestination?.route == SAFE_TX_ROUTE) return
    val digest = Digest.parse(hash) ?: return
    navigate("${Destination.SAFE}/${safe.hex}/${Destination.SAFE_TX}/0x${digest.hex}") {
        if (replacing != null) popUpTo(replacing) { inclusive = true }
    }
    // The entry just opened, before it is first drawn: its ViewModel takes the flag when it is made.
    if (imported) currentBackStackEntry?.savedStateHandle?.set(IMPORTED, true)
}

/**
 * Pops the destination of [pattern] and everything above it, and nothing else: a second tap
 * during the exit animation, or a screen whose account vanished under a dialog, pops only
 * what is still there.
 */
private fun NavController.leave(pattern: String) {
    if (!popBackStack("$pattern/{$ADDRESS}", inclusive = true)) popBackStack(pattern, inclusive = true)
}

/** A screen of one account, at `pattern/{address}`; a route without a valid address leaves at once. */
private fun NavGraphBuilder.withAddress(
    pattern: String,
    navController: NavController,
    content: @Composable (Address) -> Unit,
) {
    composable("$pattern/{$ADDRESS}") { entry -> WithAddress(entry, pattern, navController, content) }
}

/** As [withAddress], for a dialog over the screen below it. */
private fun NavGraphBuilder.dialogWithAddress(
    pattern: String,
    navController: NavController,
    content: @Composable (Address) -> Unit,
) {
    dialog("$pattern/{$ADDRESS}") { entry -> WithAddress(entry, pattern, navController, content) }
}

@Composable
private fun WithAddress(
    entry: NavBackStackEntry,
    pattern: String,
    navController: NavController,
    content: @Composable (Address) -> Unit,
) {
    val address = entry.savedStateHandle.get<String>(ADDRESS)?.let(Address::parse)
    if (address != null) {
        content(address)
    } else {
        LaunchedEffect(Unit) { navController.leave(pattern) }
    }
}

private object Destination {
    const val ASSETS = "assets"
    const val ACCOUNT = "account"
    const val RECEIVE = "receive"
    const val ACTIVITY = "activity"
    const val WATCH = "watch"
    const val RENAME = "rename"
    const val REMOVE = "remove"
    const val SETTINGS = "settings"
    const val CURRENCY = "currency"
    const val TOKENS = "tokens"
    const val SECURITY = "security"
    const val ADD_TOKEN = "add-token"
    const val MNEMONIC_IMPORT = "mnemonic-import"
    const val CREATE_ACCOUNT = "create-account"
    const val CREATE_SAFE = "create-safe"
    const val SEND = "send"
    const val SWAP = "swap"
    const val TRANSFER = "transfer"
    const val SAFE = "safe"
    const val SAFE_TX = "tx"
    const val PROPOSE = "propose"
    const val IMPORT = "import"
    const val UPDATE_REQUIRED = "update-required"
}

/** The route argument every account destination carries. */
private const val ADDRESS = "address"

/** Send's and Swap's optional route argument: the contract of the token chosen (sold, for a swap). */
private const val ASSET = "asset"

/** The transfer status's and a Safe transaction's route argument: the transaction hash. */
private const val HASH = "hash"

/** A Safe's swap's optional route argument: the hash of the queued swap it is proposed again in place of. */
private const val REPLACING = "replacing"

/** Set on a Safe transaction's entry that an import has just queued, until its ViewModel takes it. */
private const val IMPORTED = "imported"

/** Send from an account, with a token chosen or not. */
private const val SEND_ROUTE = "${Destination.SEND}/{$ADDRESS}?$ASSET={$ASSET}"

/** Swap from an account, selling a token or the network's currency. */
private const val SWAP_ROUTE = "${Destination.SWAP}/{$ADDRESS}?$ASSET={$ASSET}"

/** Where a transfer stands, by its hash. */
private const val TRANSFER_ROUTE = "${Destination.TRANSFER}/{$HASH}"

/** A Safe's transaction, by the Safe and the transaction's hash. */
private const val SAFE_TX_ROUTE = "${Destination.SAFE}/{$ADDRESS}/${Destination.SAFE_TX}/{$HASH}"

/** Send from a Safe: a Safe transaction proposed, with a token chosen or not. */
private const val SAFE_PROPOSE_ROUTE = "${Destination.SAFE}/{$ADDRESS}/${Destination.PROPOSE}?$ASSET={$ASSET}"

/** Swap from a Safe: its Safe transactions proposed, selling a token or not, or a queued swap again. */
private const val SAFE_SWAP_ROUTE =
    "${Destination.SAFE}/{$ADDRESS}/${Destination.SWAP}?$ASSET={$ASSET}&$REPLACING={$REPLACING}"

/** Import a signing request for a Safe. */
private const val SAFE_IMPORT_ROUTE = "${Destination.SAFE}/{$ADDRESS}/${Destination.IMPORT}"

/** A transaction hash (a Safe transaction's too) as a route carries it: `0x` and 64 lower-case hex digits. */
private val TRANSACTION_HASH = Regex("^0x[0-9a-f]{64}$")

/** From this width on, the tabs are a rail at the side instead of a bar at the bottom. */
private val WIDE_WINDOW = 840.dp
