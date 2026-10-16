package eth.serotine.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.domain.model.Network
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JOptionPane
import kotlin.concurrent.thread

/**
 * The desktop composition root: builds the [AppGraph] from this platform's adapters and
 * opens the one window on the shared [App]. The window provides the ViewModelStoreOwner
 * the navigation back stack keeps its screens' ViewModels in. One instance per user: a
 * second one finds the [InstanceLock] taken, says so and ends.
 */
fun main() {
    val dataDir = appDataDir()
    val instance = InstanceLock.acquire(dataDir.resolve("serotine.lock"))
    if (instance == null) {
        val message = "Serotine is already running."
        System.err.println(message)
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Serotine", JOptionPane.INFORMATION_MESSAGE)
        }
        return
    }
    // use: holds the lock, and keeps it reachable, until the application ends.
    instance.use { openWallet(dataDir) }
}

private fun openWallet(dataDir: File) {
    val keys = dataDir.resolve("keys")
    excludeFromBackups(keys)
    application {
        val graph = remember {
            AppGraph(
                accounts = FileAccountRepository(dataDir.resolve("accounts.json").path),
                // The password alone where there is no hardware key store (the owner's
                // ruling of 2026-10-15). A Mac's Secure Enclave waits for a signed app: it
                // gives keys only to an app with a keychain-access-group entitlement.
                keys = EnvelopeKeyStore(keys.path, hardware = null),
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
}

/**
 * Keeps [directory], the key store's envelopes, out of backups (docs/keys-and-signing.md,
 * rule 8) where this OS backs application data up on its own: Time Machine on macOS, told
 * through its sticky exclusion, which needs no administrator rights and moves with the
 * directory. Windows' File History and the usual Linux tools leave application data out
 * unless the user adds it. Best effort, off the main thread; a failure is reported.
 */
private fun excludeFromBackups(directory: File) {
    if ("mac" !in System.getProperty("os.name").lowercase()) return
    thread(isDaemon = true, name = "serotine-backup-exclusion") {
        try {
            directory.mkdirs()
            val process = ProcessBuilder("/usr/bin/tmutil", "addexclusion", directory.path)
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.readBytes() }
            val exit = process.waitFor()
            if (exit != 0) println("Serotine: Time Machine did not exclude $directory (exit $exit)")
        } catch (e: Exception) {
            println("Serotine: Time Machine did not exclude $directory (${e::class.simpleName})")
        }
    }
}

/** Where this OS keeps an application's own data: the account list, the keys and the instance lock. */
private fun appDataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = File(System.getProperty("user.home"))
    return when {
        "mac" in os -> home.resolve("Library/Application Support/Serotine")
        "win" in os -> (System.getenv("LOCALAPPDATA")?.let(::File) ?: home.resolve("AppData/Local")).resolve("Serotine")
        else -> (System.getenv("XDG_DATA_HOME")?.let(::File) ?: home.resolve(".local/share")).resolve("serotine")
    }
}
