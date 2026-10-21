package eth.serotine.ui.ratewidget

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.ui.components.age
import eth.serotine.ui.components.formatFiat
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a home-screen widget shows of the exchange rate last verified (docs/wallet-ux.md,
 * *Home-screen widget*), display-ready: the [pair] ("ETH · EUR"), the [rate] ("€2,000.50"), its
 * [change] over the 24 hours before it was read ("24h +2.31%", "24h −1.05%", "24h —" when none
 * was verified; null without a rate) in the direction [trend] says, and the [note] that dates it
 * and says where it comes from ("Verified 14:05 · may have changed since · testnet prices"). The host draws it in its own
 * widget toolkit, green for [Trend.UP] and red for [Trend.DOWN].
 */
data class RateWidgetContent(
    val pair: String,
    val rate: String,
    val change: String?,
    val trend: Trend,
    val note: String,
)

/** Which way a rate moved in 24 hours, as far as its shown digits tell. */
enum class Trend { UP, DOWN, FLAT, UNKNOWN }

/**
 * [snapshot] as the widget shows it on [network], read at [readAt] (the host's own rendering of
 * [RateSnapshot.readAt] in the user's locale, "Oct 21, 14:05"); no rate kept yet says so. Being
 * a rate kept and shown later, it says it may have changed since it was verified; one whose
 * feeds were stale says how old its price is. On a testnet every rate says "testnet
 * prices" (CLAUDE.md, *Data sources*).
 */
fun rateWidgetContent(snapshot: RateSnapshot?, network: Network, readAt: String): RateWidgetContent {
    if (snapshot == null) {
        return RateWidgetContent(
            pair = network.nativeSymbol,
            rate = "—",
            change = null,
            trend = Trend.UNKNOWN,
            note = "No verified rate yet",
        )
    }
    val percent = snapshot.dayChangePercent
    // In hundredths of a percent, the digits shown: what rounds to zero is no move.
    val hundredths = percent?.let { (it * 100).roundToLong() }
    return RateWidgetContent(
        pair = "${snapshot.base} · ${snapshot.quote.code}",
        rate = formatFiat(snapshot.rate, snapshot.quote),
        change = "24h " + (hundredths?.let(::signedPercent) ?: "—"),
        trend = when {
            hundredths == null -> Trend.UNKNOWN
            hundredths > 0 -> Trend.UP
            hundredths < 0 -> Trend.DOWN
            else -> Trend.FLAT
        },
        note = listOfNotNull(
            "Verified $readAt",
            // Kept and shown later: an older answer, labelled as one (CLAUDE.md, *Trust*).
            "may have changed since",
            "price ${age(snapshot.readAt - snapshot.updatedAt)} old".takeIf { snapshot.stale },
            "testnet prices".takeIf { network.testnet },
        ).joinToString(" · "),
    )
}

/** 231 → "+2.31%", −105 → "−1.05%" (a minus sign, not a hyphen), 0 → "0.00%". */
private fun signedPercent(hundredths: Long): String {
    val sign = when {
        hundredths > 0 -> "+"
        hundredths < 0 -> "−"
        else -> ""
    }
    val magnitude = abs(hundredths)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}%"
}
