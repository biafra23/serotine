package eth.serotine.domain.usecase

import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.KnownUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkUpgrades
import eth.serotine.domain.repository.UpgradeNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Sepolia's Glamsterdam, as its fork id and activation time were published. */
internal val glamsterdam = NetworkUpgrade(NetworkUpgrade.Phase.SCHEDULED, 1_791_294_816, "0x6c1d9423", 5)

internal const val DAY = 24 * 60 * 60L

internal class FakeNetworkUpgrades : NetworkUpgrades {
    val report = MutableStateFlow<NetworkUpgrade?>(null)
    override fun observeUpgrade(): Flow<NetworkUpgrade?> = report
}

internal class FakeKnownUpgradeStore : KnownUpgradeStore {
    val record = MutableStateFlow<KnownUpgrade?>(null)
    var saves = 0
    var failing = false

    override fun observe(): Flow<KnownUpgrade?> = record

    override suspend fun save(record: KnownUpgrade): Boolean {
        if (failing) return false
        saves++
        this.record.value = record
        return true
    }
}

internal class FakeLightClient : LightClient {
    val status = MutableStateFlow<LightClientStatus>(LightClientStatus.Starting)
    override fun observeStatus(): Flow<LightClientStatus> = status
}

internal class FakeNotifier : UpgradeNotifier {
    val shown = mutableListOf<UpgradeNotice>()
    val reminders = mutableListOf<Long?>()

    override fun show(notice: UpgradeNotice) {
        shown += notice
    }

    override fun remindAt(at: Long?) {
        reminders += at
    }
}
