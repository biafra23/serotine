package eth.serotine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eth.serotine.ui.home.HomeRoute
import eth.serotine.ui.mnemonicimport.MnemonicImportRoute
import eth.serotine.ui.theme.SerotineTheme
import eth.serotine.ui.update.UpdateRequiredRoute
import eth.serotine.ui.update.UpdateRequiredUiState

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters. While a network
 * upgrade has left this version unable to follow the network, it shows the update screen and
 * nothing else.
 */
@Composable
fun App(graph: AppGraph) {
    SerotineTheme {
        val navController = rememberNavController()
        // Scoped to the host, not to a destination: it decides which destinations there are.
        val update = viewModel { graph.updateRequiredViewModel() }
        val updateState by update.uiState.collectAsStateWithLifecycle()
        val updateRequired = updateState is UpdateRequiredUiState.Required
        LaunchedEffect(updateRequired) {
            if (updateRequired) {
                // The whole back stack goes: every other screen's ViewModel is cleared, the seed
                // screen's words with it, and back leaves the app instead of reaching a screen.
                navController.navigate(Destination.UPDATE_REQUIRED) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            } else if (navController.currentDestination?.route == Destination.UPDATE_REQUIRED) {
                navController.navigate(Destination.HOME) {
                    popUpTo(Destination.UPDATE_REQUIRED) { inclusive = true }
                }
            }
        }
        // One destination per screen. Inside a destination, viewModel {} is scoped to its
        // back-stack entry: the ViewModel survives configuration changes while the screen is
        // on the back stack, and is cleared when the screen is popped off it.
        NavHost(navController = navController, startDestination = Destination.HOME) {
            composable(Destination.HOME) {
                HomeRoute(
                    viewModel = viewModel { graph.homeViewModel() },
                    onAddAccount = { navController.navigate(Destination.MNEMONIC_IMPORT) { launchSingleTop = true } },
                )
            }
            composable(Destination.MNEMONIC_IMPORT) {
                // Pops only this screen, so a second tap during the exit animation is a no-op.
                val leave: () -> Unit = { navController.popBackStack(Destination.MNEMONIC_IMPORT, inclusive = true) }
                MnemonicImportRoute(
                    viewModel = viewModel { graph.mnemonicImportViewModel() },
                    onBack = leave,
                    onImported = leave,
                )
            }
            composable(Destination.UPDATE_REQUIRED) {
                UpdateRequiredRoute(viewModel = update)
            }
        }
    }
}

private object Destination {
    const val HOME = "home"
    const val MNEMONIC_IMPORT = "mnemonic-import"
    const val UPDATE_REQUIRED = "update-required"
}
