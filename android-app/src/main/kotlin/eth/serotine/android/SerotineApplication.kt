package eth.serotine.android

import android.app.Application
import eth.serotine.data.account.InMemoryAccountRepository
import eth.serotine.data.keys.MnemonicAccountDerivation
import eth.serotine.ui.AppGraph

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform. Built once per process; activities read [graph].
 */
class SerotineApplication : Application() {

    val graph: AppGraph by lazy {
        AppGraph(
            accounts = InMemoryAccountRepository(),
            accountDerivation = MnemonicAccountDerivation(),
        )
    }
}
