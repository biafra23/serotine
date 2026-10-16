package eth.serotine.android

import android.app.Application
import eth.serotine.data.account.FileAccountRepository
import eth.serotine.data.keys.AndroidKeystoreSecret
import eth.serotine.data.keys.EnvelopeKeyStore
import eth.serotine.domain.model.Network
import eth.serotine.ui.AppGraph

/**
 * The Android composition root: the one place that knows which adapters implement the
 * domain's ports on this platform. Built once per process; activities read [graph].
 */
class SerotineApplication : Application() {

    val graph: AppGraph by lazy {
        AppGraph(
            // The account list in the app's private files, which the manifest keeps out of
            // backups and device transfers; addresses and labels only, no keys.
            accounts = FileAccountRepository(filesDir.resolve("accounts.json").path),
            // Imported seeds in envelopes under the app's private files, the hardware factor
            // a Keystore HMAC key (docs/keys-and-signing.md); kept out of backups and transfers.
            keys = EnvelopeKeyStore(filesDir.resolve("keys").path, AndroidKeystoreSecret()),
            network = Network.SEPOLIA,
        )
    }
}
