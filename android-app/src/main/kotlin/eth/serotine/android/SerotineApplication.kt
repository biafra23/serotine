package eth.serotine.android

import android.app.Application
import eth.serotine.data.account.InMemoryAccountRepository
import eth.serotine.data.engine.AndroidDnsServers
import eth.serotine.data.engine.MyotisNode
import eth.serotine.data.keys.MnemonicAccountDerivation
import eth.serotine.domain.model.Network
import eth.serotine.ui.AppGraph

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform, and the owner of the process's one Myotis engine
 * (docs/embedding-myotis.md). Built once per process; activities read [graph].
 */
class SerotineApplication : Application() {

    val graph: AppGraph by lazy {
        // Sepolia only, for now (CLAUDE.md, *Data sources*). The engine boots in the
        // background and keeps its state under the app's private files; on an ABI the
        // engine library does not ship (it has arm64-v8a and x86_64), balances say so. It
        // runs as long as the process: a foreground service comes with background sync.
        val node = MyotisNode.start(Network.SEPOLIA, filesDir.resolve("myotis"), AndroidDnsServers(this))
        AppGraph(
            accounts = InMemoryAccountRepository(),
            accountDerivation = MnemonicAccountDerivation(),
            nativeBalances = node.nativeBalances,
            lightClient = node.lightClient,
            network = Network.SEPOLIA,
        )
    }
}
