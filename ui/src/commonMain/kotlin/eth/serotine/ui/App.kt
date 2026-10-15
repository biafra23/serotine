package eth.serotine.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eth.serotine.ui.home.HomeRoute
import eth.serotine.ui.mnemonicimport.MnemonicImportRoute
import eth.serotine.ui.theme.SerotineTheme

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters.
 */
@Composable
fun App(graph: AppGraph) {
    SerotineTheme {
        val navController = rememberNavController()
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
        }
    }
}

private object Destination {
    const val HOME = "home"
    const val MNEMONIC_IMPORT = "mnemonic-import"
}
