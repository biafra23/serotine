package eth.serotine.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import eth.serotine.ui.home.HomeRoute
import eth.serotine.ui.theme.SerotineTheme

/**
 * The whole shared UI, identical on every host. A host renders this inside its own
 * window or activity, after building the [graph] with its platform adapters.
 */
@Composable
fun App(graph: AppGraph) {
    SerotineTheme {
        // Scoped to the host's ViewModelStoreOwner (the Activity on Android, the window on
        // desktop), so the ViewModel survives configuration changes and is cleared with it.
        HomeRoute(viewModel = viewModel { graph.homeViewModel() })
    }
}
