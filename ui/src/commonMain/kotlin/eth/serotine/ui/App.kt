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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import eth.serotine.domain.model.Address
import eth.serotine.ui.accountdetail.AccountDetailRoute
import eth.serotine.ui.addtoken.AddTokenRoute
import eth.serotine.ui.assets.AccountAction
import eth.serotine.ui.assets.AssetsRoute
import eth.serotine.ui.components.SerotineIcons
import eth.serotine.ui.mnemonicimport.MnemonicImportRoute
import eth.serotine.ui.receive.ReceiveRoute
import eth.serotine.ui.removeaccount.RemoveAccountRoute
import eth.serotine.ui.renameaccount.RenameAccountRoute
import eth.serotine.ui.settings.SettingsRoute
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.tokens.TokensRoute
import eth.serotine.ui.watch.WatchRoute

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters.
 *
 * Two tabs, Assets and Settings, under a bottom bar (a rail on a wide window), around the
 * one [NavHost] (docs/architecture.md). Each tab is a nested graph with its own back stack;
 * a flow that ends in a key operation covers the bar.
 */
@Composable
fun App(graph: AppGraph) = App(graph, rememberNavController())

/** [App] with the [navController] a test drives. */
@Composable
internal fun App(graph: AppGraph, navController: NavHostController) {
    SerotineTheme {
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
                    Destinations(graph, navController, Modifier.padding(padding).consumeWindowInsets(padding))
                }
            }
        }
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

@Composable
private fun Destinations(graph: AppGraph, navController: NavHostController, modifier: Modifier = Modifier) {
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
                    onAccountAction = { action -> navController.open(action) },
                )
            }
            composable(Destination.WATCH) {
                WatchRoute(
                    viewModel = viewModel { graph.watchViewModel() },
                    onBack = { navController.leave(Destination.WATCH) },
                    onAdded = { navController.leave(Destination.WATCH) },
                )
            }
            withAddress(Destination.ACCOUNT, navController) { address ->
                AccountDetailRoute(
                    viewModel = viewModel { graph.accountDetailViewModel(address) },
                    onBack = { navController.leave(Destination.ACCOUNT) },
                    onGone = { navController.leave(Destination.ACCOUNT) },
                    onReceive = { navController.open(AccountAction.Receive(address.hex)) },
                    onRename = { navController.open(AccountAction.Rename(address.hex)) },
                    onRemove = { navController.open(AccountAction.Remove(address.hex)) },
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
                    onTokens = { navController.navigate(Destination.TOKENS) { launchSingleTop = true } },
                )
            }
            composable(Destination.TOKENS) {
                TokensRoute(
                    viewModel { graph.tokensViewModel() },
                    onBack = { navController.leave(Destination.TOKENS) },
                    onAddToken = { navController.navigate(Destination.ADD_TOKEN) { launchSingleTop = true } },
                )
            }
            composable(Destination.ADD_TOKEN) {
                AddTokenRoute(
                    viewModel { graph.addTokenViewModel() },
                    onBack = { navController.leave(Destination.ADD_TOKEN) },
                    onAdded = { navController.leave(Destination.ADD_TOKEN) },
                )
            }
        }
        // Outside the tabs: a key operation covers the bar until it ends.
        composable(Destination.MNEMONIC_IMPORT) {
            MnemonicImportRoute(
                viewModel = viewModel { graph.mnemonicImportViewModel() },
                onBack = { navController.leave(Destination.MNEMONIC_IMPORT) },
                onImported = { navController.leave(Destination.MNEMONIC_IMPORT) },
            )
        }
    }
}

/** Opens what [action] asks for; Copy is the screen's own, done on the clipboard. */
private fun NavController.open(action: AccountAction) {
    val route = when (action) {
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
    const val WATCH = "watch"
    const val RENAME = "rename"
    const val REMOVE = "remove"
    const val SETTINGS = "settings"
    const val TOKENS = "tokens"
    const val ADD_TOKEN = "add-token"
    const val MNEMONIC_IMPORT = "mnemonic-import"
}

/** The route argument every account destination carries. */
private const val ADDRESS = "address"

/** From this width on, the tabs are a rail at the side instead of a bar at the bottom. */
private val WIDE_WINDOW = 840.dp
