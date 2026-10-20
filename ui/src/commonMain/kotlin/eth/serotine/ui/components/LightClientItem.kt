package eth.serotine.ui.components

import eth.serotine.domain.repository.LightClientStatus
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The light client's state as the status strip shows it (on Accounts and every other screen that
 * depends on the light client) and Settings' row: [text] always; [progress] (0 to 1) when there
 * is a fraction to show, null for an open-ended wait; [working] while the client is on its way
 * to serving verified reads; and its [health], the strip's colour.
 */
data class LightClientItem(
    val text: String,
    val progress: Float?,
    val working: Boolean,
    val health: LightClientHealth,
) {

    companion object {
        /** [status] as the strip shows it at [now] (unix seconds), which dates the head block. */
        fun from(status: LightClientStatus, now: Long): LightClientItem = when (status) {
            LightClientStatus.Starting -> LightClientItem("Starting the light client…", null, true, NOT_VERIFIED)
            is LightClientStatus.Bootstrapping -> LightClientItem(
                if (status.lookingForServers) {
                    "Bootstrapping from the trusted checkpoint · looking for light-client servers"
                } else {
                    "Bootstrapping from the trusted checkpoint"
                },
                null,
                true,
                NOT_VERIFIED,
            )
            is LightClientStatus.CatchingUp -> when {
                status.total <= 0 -> LightClientItem("Verifying sync committees", null, true, NOT_VERIFIED)
                status.done >= status.total -> LightClientItem("Finishing sync", null, true, NOT_VERIFIED)
                else -> LightClientItem(
                    "Verifying sync committees · ${status.done} of ${status.total} periods",
                    status.done.toFloat() / status.total,
                    true,
                    NOT_VERIFIED,
                )
            }
            is LightClientStatus.WaitingForHead -> when (val headAge = status.headAgeSeconds) {
                null -> LightClientItem("Synced · waiting for the first verified head", null, true, NOT_VERIFIED)
                else -> LightClientItem(
                    "Synced · the verified head is ${age(headAge)} old, waiting for a fresh one",
                    null,
                    true,
                    STALE,
                )
            }
            is LightClientStatus.WaitingForProofPeers -> LightClientItem(
                "Synced to block ${grouped(status.headBlock)}${since(status.headTimestamp, now)} · " +
                    "looking for peers that serve state proofs",
                null,
                true,
                STALE,
            )
            is LightClientStatus.Synced -> LightClientItem(
                "Verified at block ${grouped(status.headBlock)}${since(status.headTimestamp, now)}",
                null,
                false,
                VERIFIED,
            )
            LightClientStatus.Sleeping ->
                LightClientItem("Light client asleep while the app is in the background", null, false, STALE)
            is LightClientStatus.StaleAnchor -> LightClientItem(
                "Sync refused: the trust anchor is ${status.anchorAgePeriods} periods old" +
                    if (status.boundPeriods > 0) ", past the limit of ${status.boundPeriods}" else "",
                null,
                false,
                NOT_VERIFIED,
            )
            LightClientStatus.Unavailable ->
                LightClientItem("No light client on this device", null, false, NOT_VERIFIED)
        }

        /** " · 8 s ago" for a block produced at [timestamp], or nothing when its time is unknown. */
        private fun since(timestamp: Long?, now: Long): String = timestamp?.let {
            val seconds = now - it
            if (seconds < 1) " · just now" else " · ${age(seconds)} ago"
        } ?: ""

        private fun age(seconds: Long): String = when {
            seconds < 120 -> "$seconds s"
            seconds < 7_200 -> "${seconds / 60} min"
            else -> "${seconds / 3_600} h"
        }
    }
}

/**
 * How far the light client is from serving verified reads, the status strip's colour (owner's
 * request, 2026-10-19). Its text always says the same in words; the colour is never all it says.
 */
enum class LightClientHealth {
    /** Green: reads are verified at a fresh verified head. */
    VERIFIED,

    /**
     * Yellow: no fresh head to read at right now, for a reason that passes on its own — the
     * verified head is older than reads allow, no peer serves state proofs, or the light
     * client is asleep while the app is in the background — so balances show as last
     * verified, dated.
     */
    STALE,

    /** Red: no verified head yet (starting, bootstrapping, catching up), sync refused, or no light client. */
    NOT_VERIFIED,
}

/**
 * These states as the strip shows them, dated by [clock] (unix seconds): re-dated every
 * second while one shows a head block's age, unchanged otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<LightClientStatus>.asItems(clock: () -> Long): Flow<LightClientItem> = flatMapLatest { status ->
    val dated = status is LightClientStatus.Synced && status.headTimestamp != null ||
        status is LightClientStatus.WaitingForProofPeers && status.headTimestamp != null
    val ticks = if (dated) everySecond else flowOf(Unit)
    ticks.map { LightClientItem.from(status, clock()) }
}

/** Now, in unix seconds: the clock the screens date the head block against. */
@OptIn(ExperimentalTime::class)
fun epochSecondsNow(): Long = Clock.System.now().epochSeconds

private val everySecond = flow {
    while (true) {
        emit(Unit)
        delay(1.seconds)
    }
}
