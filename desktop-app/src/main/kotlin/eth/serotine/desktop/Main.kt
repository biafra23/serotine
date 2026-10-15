package eth.serotine.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.keys.MnemonicAccountDerivation
import eth.serotine.domain.model.Network
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import java.io.File

/**
 * The desktop composition root: builds the [AppGraph] from this platform's adapters and
 * opens the one window on the shared [App]. The window provides the ViewModelStoreOwner
 * the navigation back stack keeps its screens' ViewModels in.
 */
fun main() = application {
    val graph = remember {
        AppGraph(
            accounts = FileAccountRepository(appDataDir().resolve("accounts.json").path),
            accountDerivation = MnemonicAccountDerivation(),
            network = Network.SEPOLIA,
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

/** Where this OS keeps an application's own data: the account list goes below it. */
private fun appDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = File(System.getProperty("user.home"))
    return when {
        "mac" in os -> home.resolve("Library/Application Support/Serotine")
        "win" in os -> (System.getenv("LOCALAPPDATA")?.let(::File) ?: home.resolve("AppData/Local")).resolve("Serotine")
        else -> (System.getenv("XDG_DATA_HOME")?.let(::File) ?: home.resolve(".local/share")).resolve("serotine")
    }
}
