package eth.serotine.ui.settings

import eth.serotine.ui.components.LightClientItem

/**
 * Everything the Settings screen renders (docs/wallet-ux.md, screen 18), as far as the
 * wallet has settings yet: the display [currency] ("EUR"), the [tokens] it knows ("3 built
 * in · 1 added"), the [network] it works on, whether the heavy downloads may use a metered
 * connection ([meteredDownloads]), the [lightClient]'s state, and the app's [version]. The
 * security settings join it with the features they belong to.
 */
data class SettingsUiState(
    val network: String,
    val lightClient: LightClientItem,
    val version: String,
    val tokens: String = "",
    val currency: String = "",
    /** Null until the choice and the connection are read: the switch is then drawn off and greyed out. */
    val meteredDownloads: MeteredDownloadsItem? = null,
)

/**
 * The switch that allows the heavy downloads, such as the walk back over the account history, on
 * a metered connection: [on] when allowed, [text] what that means now ("Off: … · Waiting now"), and
 * [unavailableReason] when it cannot be changed (on this device, or while the choice cannot be
 * read), said on a tap of the greyed row.
 */
data class MeteredDownloadsItem(val on: Boolean, val text: String, val unavailableReason: String? = null) {
    companion object {
        /** The opt-in default: not allowed, on a connection that is not metered. */
        val NOT_ALLOWED = MeteredDownloadsItem(on = false, text = METERED_DOWNLOADS_OFF)
    }
}

/** What the switch says while the heavy downloads are not allowed on a metered connection. */
internal const val METERED_DOWNLOADS_OFF = "Off: the account history is walked back only on Wi-Fi or another " +
    "unmetered connection"

/** What the switch says while they are. */
internal const val METERED_DOWNLOADS_ON = "On: the account history is walked back on any connection, mobile data too"
