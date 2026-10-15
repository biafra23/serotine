package eth.serotine.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.InMemoryAccountRepository
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph

/**
 * The desktop composition root: builds the [AppGraph] from this platform's adapters and
 * opens the one window on the shared [App]. The window provides the ViewModelStoreOwner
 * the ViewModels are scoped to.
 */
fun main() = application {
    val graph = remember {
        AppGraph(
            accounts = InMemoryAccountRepository(),
        )
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Serotine",
        state = rememberWindowState(width = 480.dp, height = 760.dp),
    ) {
        App(graph)
    }
}
