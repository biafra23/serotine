package eth.serotine.android

import android.app.Application
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.keys.MnemonicAccountDerivation
import eth.serotine.domain.model.Network
import eth.serotine.ui.AppGraph

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform. Built once per process; activities read [graph].
 */
class SerotineApplication : Application() {

    val graph: AppGraph by lazy {
        AppGraph(
            // The account list in the app's private files (excluded from backup by the
            // manifest's allowBackup="false"); addresses and labels only, no keys.
            accounts = FileAccountRepository(filesDir.resolve("accounts.json").path),
            accountDerivation = MnemonicAccountDerivation(),
            network = Network.SEPOLIA,
        )
    }
}
