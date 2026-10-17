package eth.serotine.domain.usecase

import eth.serotine.domain.model.NetworkUpgrade
import eth.serotine.domain.repository.KnownUpgradeStore
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.domain.repository.NetworkUpgrades
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration.Companion.seconds

/**
 * What the app must tell the user about a network upgrade this version does not support:
 * the "update the app" capability. It joins what peers report now, the upgrade recorded
 * from earlier reports (so an app opened after the upgrade knows it before peers report it
 * again) and the light client's own state, and re-decides when a known activation time
 * passes, with [clock] (unix seconds) as now.
 *
 * Peers' reports are not verified, so on their own they never make the app claim it can no
 * longer verify: [UpgradeWarning.UpdateRequired], the state in which the app shows nothing
 * but "update", needs an activated upgrade AND a light client whose own state agrees that
 * it is not verifying (not synced, or synced with a stale head) — the rule Myotis sets for
 * every host of its upgrade advisory. A light client that verifies fresh heads after the
 * upgrade gets [UpgradeWarning.Activated] instead: still working, update soon.
 */
class ObserveUpgradeWarning(
    private val upgrades: NetworkUpgrades,
    private val known: KnownUpgradeStore,
    private val lightClient: LightClient,
    private val clock: () -> Long = ::epochSecondsNow,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<UpgradeWarning> =
        combine(upgrades.observeUpgrade(), known.observe(), lightClient.observeStatus()) { report, record, status ->
            Inputs(report, record?.upgrade, status)
        }.transformLatest { inputs ->
            while (true) {
                val now = clock()
                emit(warningOf(inputs, now))
                // Nothing upstream changes when an activation time passes on the clock alone.
                val next = listOfNotNull(inputs.report, inputs.recorded)
                    .mapNotNull { it.activationTime }
                    .filter { it > now }
                    .minOrNull() ?: break
                delay((next - now).seconds)
            }
        }.distinctUntilChanged()

    private data class Inputs(
        val report: NetworkUpgrade?,
        val recorded: NetworkUpgrade?,
        val status: LightClientStatus,
    )

    private fun warningOf(inputs: Inputs, now: Long): UpgradeWarning {
        val (report, recorded, status) = inputs
        if (report != null) {
            // The record may know a time the report does not: peers already on the new rules
            // do not say when they began.
            val upgrade = recorded?.takeIf { it.isSameUpgradeAs(report) }?.updatedBy(report) ?: report
            return when {
                !upgrade.hasActivated(now) -> UpgradeWarning.Ahead(upgrade)
                status.agreesItIsNotVerifying -> UpgradeWarning.UpdateRequired(upgrade)
                else -> UpgradeWarning.Activated(upgrade)
            }
        }
        recorded ?: return UpgradeWarning.None
        return when {
            !recorded.hasActivated(now) -> UpgradeWarning.Ahead(recorded)
            status.agreesItIsNotVerifying -> UpgradeWarning.UpdateRequired(recorded)
            // Activated by the record's clock, peers report nothing now and the light client
            // verifies fresh heads: this version follows the network, nothing to say.
            else -> UpgradeWarning.None
        }
    }
}

/**
 * Whether the light client's own state agrees that it is not verifying the chain: not synced
 * yet, or synced with a head that has gone stale. Asleep (networking off on purpose) and no
 * light client at all say nothing either way.
 */
private val LightClientStatus.agreesItIsNotVerifying: Boolean
    get() = when (this) {
        LightClientStatus.Starting,
        is LightClientStatus.Bootstrapping,
        is LightClientStatus.CatchingUp,
        is LightClientStatus.WaitingForHead,
        is LightClientStatus.StaleAnchor,
        -> true
        is LightClientStatus.Synced,
        is LightClientStatus.WaitingForProofPeers,
        LightClientStatus.Sleeping,
        LightClientStatus.Unavailable,
        -> false
    }

/** What [ObserveUpgradeWarning] emits. */
sealed interface UpgradeWarning {

    /** No upgrade this version does not support is reported or recorded. */
    data object None : UpgradeWarning

    /** [upgrade] lies ahead, at its activation time or one not known yet: update before then. */
    data class Ahead(val upgrade: NetworkUpgrade) : UpgradeWarning

    /** [upgrade] has activated, and the light client still verifies: update before it stops. */
    data class Activated(val upgrade: NetworkUpgrade) : UpgradeWarning

    /**
     * [upgrade] has activated and the light client is not verifying: this version can no
     * longer follow the network. The app shows nothing but "update".
     */
    data class UpdateRequired(val upgrade: NetworkUpgrade) : UpgradeWarning
}
