package eth.serotine.ui.home

import eth.serotine.domain.repository.LightClientStatus

/**
 * The light client's state as the Home screen's status strip shows it: [text] always;
 * [progress] (0 to 1) when there is a fraction to show, null for an open-ended wait; and
 * [working] while the client is on its way to serving verified reads.
 */
data class LightClientItem(val text: String, val progress: Float?, val working: Boolean) {

    companion object {
        fun from(status: LightClientStatus): LightClientItem = when (status) {
            LightClientStatus.Starting -> LightClientItem("Starting the light client…", null, true)
            is LightClientStatus.Bootstrapping -> LightClientItem(
                if (status.lookingForServers) {
                    "Bootstrapping from the trusted checkpoint · looking for light-client servers"
                } else {
                    "Bootstrapping from the trusted checkpoint"
                },
                null,
                true,
            )
            is LightClientStatus.CatchingUp -> when {
                status.total <= 0 -> LightClientItem("Verifying sync committees", null, true)
                status.done >= status.total -> LightClientItem("Finishing sync", null, true)
                else -> LightClientItem(
                    "Verifying sync committees · ${status.done} of ${status.total} periods",
                    status.done.toFloat() / status.total,
                    true,
                )
            }
            is LightClientStatus.WaitingForHead -> LightClientItem(
                status.headAgeSeconds?.let { "Synced · the verified head is ${age(it)} old, waiting for a fresh one" }
                    ?: "Synced · waiting for the first verified head",
                null,
                true,
            )
            is LightClientStatus.WaitingForProofPeers -> LightClientItem(
                "Synced to block ${grouped(status.headBlock)} · looking for peers that serve state proofs",
                null,
                true,
            )
            is LightClientStatus.Synced ->
                LightClientItem("Verified at block ${grouped(status.headBlock)}", null, false)
            LightClientStatus.Sleeping -> LightClientItem("Light client asleep while the app is in the background", null, false)
            is LightClientStatus.StaleAnchor -> LightClientItem(
                "Sync refused: the trust anchor is ${status.anchorAgePeriods} periods old" +
                    if (status.boundPeriods > 0) ", past the limit of ${status.boundPeriods}" else "",
                null,
                false,
            )
            LightClientStatus.Unavailable -> LightClientItem("No light client on this device", null, false)
        }

        private fun age(seconds: Long): String = when {
            seconds < 120 -> "$seconds s"
            seconds < 7_200 -> "${seconds / 60} min"
            else -> "${seconds / 3_600} h"
        }

        /** 11922744 → "11,922,744". */
        private fun grouped(number: Long): String =
            number.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
