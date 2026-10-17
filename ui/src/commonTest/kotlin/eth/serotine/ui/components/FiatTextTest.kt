package eth.serotine.ui.components

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.usecase.Total
import eth.serotine.domain.usecase.Valuation
import kotlin.test.Test
import kotlin.test.assertEquals

class FiatTextTest {

    @Test
    fun aValueIsRoundedToItsCurrencysDigitsAndGrouped() {
        assertEquals("$3,250.00", formatFiat(3_250.0, DisplayCurrency.USD))
        assertEquals("€1,234.57", formatFiat(1_234.567, DisplayCurrency.EUR))
        assertEquals("¥632", formatFiat(632.4, DisplayCurrency.JPY))
        assertEquals("1.2346 ETH", formatFiat(1.23456, DisplayCurrency.ETH))
        assertEquals("CHF 0.10", formatFiat(0.1, DisplayCurrency.CHF))
    }

    @Test
    fun aTinyValueIsNeverShownAsNothingAndAHugeOneIsCapped() {
        assertEquals("< $0.01", formatFiat(0.001, DisplayCurrency.USD))
        assertEquals("< ¥1", formatFiat(0.2, DisplayCurrency.JPY))
        assertEquals("$0.00", formatFiat(0.0, DisplayCurrency.USD))
        assertEquals("$1,000,000,000,000,000+", formatFiat(1e30, DisplayCurrency.USD))
        assertEquals("1,000,000,000,000+ BTC", formatFiat(1e20, DisplayCurrency.BTC))
    }

    @Test
    fun aRowSaysItsValueOrWhyThereIsNone() {
        assertEquals("$5.00", valuationText(Valuation.Valued(5.0, 60, stale = false), DisplayCurrency.USD))
        assertEquals(
            "$5.00 · price 2 h old",
            valuationText(Valuation.Valued(5.0, 7_200, stale = true), DisplayCurrency.USD),
        )
        assertEquals("No price", valuationText(Valuation.NoPrice(NoPriceReason.NO_FEED), DisplayCurrency.USD))
        assertEquals(null, valuationText(null, DisplayCurrency.USD))
    }

    @Test
    fun aTotalSaysWhatItLeavesOut() {
        assertEquals(
            "Testnet prices · 2 assets without a price · still checking",
            totalNote(Total(1.0, leftOut = 2, complete = false), Network.SEPOLIA),
        )
        assertEquals("Checking…", totalText(Total(0.0, 0, complete = false), DisplayCurrency.USD))
    }
}
