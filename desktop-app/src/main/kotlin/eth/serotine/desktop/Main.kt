package eth.serotine.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.data.keys.desktopHardwareSecret
import eth.serotine.domain.model.Network
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.lang.ref.Reference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The desktop composition root: builds the [AppGraph] from this platform's adapters and
 * opens the one window on the shared [App]. The window provides the ViewModelStoreOwner
 * the navigation back stack keeps its screens' ViewModels in. One instance per user: a
 * second one finds the [InstanceLock] taken, says so and ends.
 */
fun main() {
    val dataDir = appDataDir()
    val instance = try {
        InstanceLock.acquire(dataDir.resolve("serotine.lock"))
    } catch (e: IOException) {
        tellAndExit(
            "Serotine cannot use its data directory $dataDir: ${e.message ?: e::class.simpleName}",
            JOptionPane.ERROR_MESSAGE,
        )
    }
    if (instance == null) tellAndExit("Serotine is already running.", JOptionPane.INFORMATION_MESSAGE)
    try {
        openWallet(dataDir)
    } finally {
        // Never released by hand: the lock ends with the process, which application() ends
        // itself; until then it must stay reachable, or the garbage collector would close it.
        Reference.reachabilityFence(instance)
    }
}

/**
 * Says [message] (a dialog of [kind], a [JOptionPane] message type, where there is a screen;
 * stderr always) and ends the process with status 1.
 */
private fun tellAndExit(message: String, kind: Int): Nothing {
    System.err.println(message)
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            SwingUtilities.invokeAndWait { JOptionPane.showMessageDialog(null, message, "Serotine", kind) }
        } catch (e: Throwable) {
            // No usable window server after all: stderr has the message, and the exit below stands.
        }
    }
    exitProcess(1)
}

private fun openWallet(dataDir: File) {
    val keys = dataDir.resolve("keys")
    excludeFromBackups(keys)
    // Before the window: on a Mac with no wallet yet this asks the Secure Enclave once.
    val hardware = desktopHardwareSecret(keys.path)
    application {
        val graph = remember {
            AppGraph(
                accounts = FileAccountRepository(dataDir.resolve("accounts.json").path),
                // A Mac's Secure Enclave where the wallet uses it or can start to, the
                // password alone otherwise (the owner's rulings of 2026-10-15 and -16).
                keys = EnvelopeKeyStore(keys.path, hardware),
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
 * rule 8) where this OS backs application data up on its own. On macOS that is Time Machine,
 * told through its sticky exclusion, which needs no administrator rights and moves with the
 * directory. On Linux it is GNOME's Backups (Déjà Dup), whose default takes the whole home
 * directory and skips a directory holding a `.deja-dup-ignore` file. Windows' File History
 * leaves application data out unless the user adds it. Best effort, off the main thread; a
 * failure is reported.
 */
private fun excludeFromBackups(directory: File) {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if ("linux" in os) {
        thread(isDaemon = true, name = "serotine-backup-exclusion") {
            try {
                directory.mkdirs()
                directory.resolve(".deja-dup-ignore").createNewFile()
            } catch (e: Exception) {
                println("Serotine: could not mark $directory for Déjà Dup (${e::class.simpleName})")
            }
        }
        return
    }
    if ("mac" !in os) return
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
        // Not %LOCALAPPDATA%\Serotine: the per-user msi installs the app there, and the keys
        // must not share a directory an uninstaller, an upgrade or a user cleaning up owns.
        "win" in os -> (System.getenv("LOCALAPPDATA")?.let(::File) ?: home.resolve("AppData/Local"))
            .resolve("eth.serotine")
        else -> (System.getenv("XDG_DATA_HOME")?.let(::File) ?: home.resolve(".local/share")).resolve("serotine")
    }
}
