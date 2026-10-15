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
                "Bootstrapping from the trusted checkpoint · ${peers(status.peers)}",
                null,
                true,
            )
            is LightClientStatus.Syncing -> LightClientItem(
                if (status.total > 0) {
                    "Verifying sync committees · ${status.done} of ${status.total} periods · ${peers(status.peers)}"
                } else {
                    "Verifying sync committees · ${peers(status.peers)}"
                },
                fraction(status.done, status.total),
                true,
            )
            is LightClientStatus.CatchingUp -> LightClientItem(
                // The engine reports its threshold as 0 until it knows it.
                if (status.needed > 0) {
                    "Collecting verified state roots · ${status.known} of ${status.needed}"
                } else {
                    "Collecting verified state roots"
                },
                fraction(status.known.toLong(), status.needed.toLong()),
                true,
            )
            is LightClientStatus.Synced ->
                LightClientItem("Verified at block ${grouped(status.headBlock)}", null, false)
            is LightClientStatus.StaleAnchor -> LightClientItem(
                "Sync refused: the trust anchor is ${status.anchorAgePeriods} periods old, " +
                    "past the limit of ${status.boundPeriods}",
                null,
                false,
            )
            LightClientStatus.Unavailable -> LightClientItem("No light client on this device", null, false)
        }

        private fun peers(count: Int): String = if (count == 1) "1 peer" else "$count peers"

        private fun fraction(done: Long, total: Long): Float? =
            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

        /** 11922744 → "11,922,744". */
        private fun grouped(number: Long): String =
            number.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
