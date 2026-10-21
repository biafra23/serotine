package eth.serotine.ui.ratewidget

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.model.RateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class RateWidgetContentTest {

    private val snapshot = RateSnapshot(
        base = PriceId("ETH"),
        quote = DisplayCurrency.EUR,
        rate = 2_046.2,
        dayAgoRate = 2_000.0,
        updatedAt = 1_000,
        readAt = 1_600,
        stale = false,
    )

    @Test
    fun aRiseIsGreenWithAPlusAndTheRateIsDatedAsTestnetPrices() {
        assertEquals(
            RateWidgetContent("ETH · EUR", "€2,046.20", "24h +2.31%", Trend.UP, "Verified 14:05 · may have changed since · testnet prices"),
            rateWidgetContent(snapshot, Network.SEPOLIA, "14:05"),
        )
    }

    @Test
    fun aFallIsRedWithAMinusSign() {
        val content = rateWidgetContent(snapshot.copy(rate = 1_979.0), Network.SEPOLIA, "14:05")

        assertEquals("24h −1.05%", content.change)
        assertEquals(Trend.DOWN, content.trend)
    }

    @Test
    fun whatRoundsToNoMoveIsFlatAndNoRateADayBackIsADash() {
        val flat = rateWidgetContent(snapshot.copy(rate = 2_000.04), Network.SEPOLIA, "14:05")
        assertEquals("24h 0.00%", flat.change)
        assertEquals(Trend.FLAT, flat.trend)

        val none = rateWidgetContent(snapshot.copy(dayAgoRate = null), Network.SEPOLIA, "14:05")
        assertEquals("24h —", none.change)
        assertEquals(Trend.UNKNOWN, none.trend)
    }

    @Test
    fun aStaleRateSaysHowOldItsPriceIs() {
        val stale = snapshot.copy(updatedAt = 1_600 - 7_200, stale = true)

        val content = rateWidgetContent(stale, Network.SEPOLIA, "14:05")

        assertEquals("Verified 14:05 · may have changed since · price 2 h old · testnet prices", content.note)
    }

    @Test
    fun noRateKeptYetSaysSo() {
        assertEquals(
            RateWidgetContent("ETH", "—", null, Trend.UNKNOWN, "No verified rate yet"),
            rateWidgetContent(null, Network.SEPOLIA, ""),
        )
    }
}
