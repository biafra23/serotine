package eth.serotine.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.InMemoryAccountRepository
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.keys.MnemonicAccountDerivation
import eth.serotine.domain.model.Network
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import java.io.File

/**
 * The desktop composition root: starts the process's one Myotis engine
 * (docs/embedding-myotis.md), builds the [AppGraph] from this platform's adapters and opens
 * the one window on the shared [App]. The window provides the ViewModelStoreOwner the
 * navigation back stack keeps its screens' ViewModels in; closing it stops the engine.
 */
fun main() {
    // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the
    // background; without its host library on java.library.path, balances say so.
    val node = MyotisNode.start(Network.SEPOLIA, appDataDir().resolve("myotis"))
    application {
        val graph = remember {
            AppGraph(
                accounts = InMemoryAccountRepository(),
                accountDerivation = MnemonicAccountDerivation(),
                nativeBalances = node.nativeBalances,
                lightClient = node.lightClient,
                network = Network.SEPOLIA,
            )
        }
        Window(
            onCloseRequest = {
                node.shutdown()
                exitApplication()
            },
            title = "Serotine",
            state = rememberWindowState(width = 480.dp, height = 760.dp),
        ) {
            App(graph)
        }
    }
}

/** Where this OS keeps an application's own data: the engine's state goes below it. */
private fun appDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = File(System.getProperty("user.home"))
    return when {
        "mac" in os -> home.resolve("Library/Application Support/Serotine")
        "win" in os -> (System.getenv("LOCALAPPDATA")?.let(::File) ?: home.resolve("AppData/Local")).resolve("Serotine")
        else -> (System.getenv("XDG_DATA_HOME")?.let(::File) ?: home.resolve(".local/share")).resolve("serotine")
    }
}
