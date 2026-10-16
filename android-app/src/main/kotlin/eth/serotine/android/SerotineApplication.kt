package eth.serotine.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.engine.AndroidDnsServers
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.domain.model.Network
import eth.serotine.ui.AppGraph

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform, and the owner of the process's one Myotis engine
 * (docs/embedding-myotis.md). Built once per process; activities read [graph].
 */
class SerotineApplication : Application() {

    // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the background
    // and keeps its state under the app's private files; on an ABI the engine library does
    // not ship (it has arm64-v8a and x86_64), balances say so.
    private val node: MyotisNode by lazy {
        MyotisNode.start(Network.SEPOLIA, filesDir.resolve("myotis"), AndroidDnsServers(this))
    }

    val graph: AppGraph by lazy {
        AppGraph(
            // The account list in the app's private files, which the manifest keeps out of
            // backups and device transfers; addresses and labels only, no keys.
            accounts = FileAccountRepository(filesDir.resolve("accounts.json").path),
            // Imported seeds in envelopes under the app's private files, the hardware factor
            // a Keystore HMAC key (docs/keys-and-signing.md); kept out of backups and transfers.
            keys = EnvelopeKeyStore(filesDir.resolve("keys").path, AndroidKeystoreSecret()),
            nativeBalances = node.nativeBalances,
            lightClient = node.lightClient,
            network = Network.SEPOLIA,
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(PauseInBackground())
    }

    /**
     * Turns the engine's networking off once no activity has been visible for
     * [PAUSE_DELAY_MS], and back on when one is again: a backgrounded wallet keeps no sockets
     * or timers open. The delay keeps a rotation or a quick app switch from pausing at all.
     * A foreground service comes with background sync.
     */
    private inner class PauseInBackground : ActivityLifecycleCallbacks {
        private var visible = 0
        private val handler = Handler(Looper.getMainLooper())
        private val pause = Runnable { node.pause() }

        override fun onActivityStarted(activity: Activity) {
            if (visible++ == 0) {
                handler.removeCallbacks(pause)
                node.resume()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (--visible == 0) handler.postDelayed(pause, PAUSE_DELAY_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val PAUSE_DELAY_MS = 30_000L
    }
}
