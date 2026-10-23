package eth.serotine.android.widget

import android.content.Context
import eth.serotine.domain.model.Network

/**
 * Which network each placed exchange-rate widget shows (docs/networks.md, *The widget*), by its
 * app-widget id: chosen when it is placed ([RateWidgetConfigureActivity]) and kept until it is
 * removed. A widget placed before networks has none recorded and shows Sepolia, the only network
 * it could have shown. Private preferences: the choice is no secret, but no other app needs it.
 */
class RateWidgetNetworks(context: Context) {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The network the widget [id] shows. */
    fun of(id: Int): Network =
        preferences.getLong(key(id), 0).takeIf { it != 0L }?.let(Network::ofChainId) ?: Network.SEPOLIA

    /** Whether the widget [id] has its network recorded: chosen once, when it was placed. */
    fun chosen(id: Int): Boolean = preferences.contains(key(id))

    /** Records that the widget [id] shows [network]; true once stored. */
    fun set(id: Int, network: Network): Boolean = preferences.edit().putLong(key(id), network.chainId).commit()

    /** Forgets the widgets [ids], removed from the home screen. */
    fun forget(ids: IntArray) {
        preferences.edit().apply { ids.forEach { remove(key(it)) } }.apply()
    }

    private fun key(id: Int) = "network-$id"

    private companion object {
        const val FILE = "rate-widget-networks"
    }
}
