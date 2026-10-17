package eth.serotine.ui.components

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.BalanceReading.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceTextTest {

    @Test
    fun wholeAndFractionalAmounts() {
        assertEquals("0 ETH", formatNative(Wei.of("0"), "ETH"))
        assertEquals("1 ETH", formatNative(Wei.of("1000000000000000000"), "ETH"))
        assertEquals("2.5 ETH", formatNative(Wei.of("2500000000000000000"), "ETH"))
        assertEquals("1234567.0001 ETH", formatNative(Wei.of("1234567000100000000000000"), "ETH"))
    }

    @Test
    fun truncatesNeverRoundsUp() {
        // 0.99999 ETH shows as 0.9999, not as 1.
        assertEquals("0.9999 ETH", formatNative(Wei.of("999990000000000000"), "ETH"))
    }

    @Test
    fun aDustBalanceIsNotShownAsZero() {
        assertEquals("< 0.0001 ETH", formatNative(Wei.of("1"), "ETH"))
        assertEquals("< 0.0001 ETH", formatNative(Wei.of("99999999999999"), "ETH"))
    }

    @Test
    fun everyReadingHasAText() {
        assertEquals("Syncing…", balanceText(BalanceReading.Syncing, Network.SEPOLIA))
        assertEquals("0.5 ETH", balanceText(BalanceReading.Verified(Wei.of("500000000000000000"), 1), Network.SEPOLIA))
        assertEquals("No light client", balanceText(BalanceReading.Unavailable(Reason.NO_ENGINE), Network.SEPOLIA))
        assertEquals("Sync refused", balanceText(BalanceReading.Unavailable(Reason.STALE_ANCHOR), Network.SEPOLIA))
        assertEquals("Not verified", balanceText(BalanceReading.Unavailable(Reason.UNPROVEN), Network.SEPOLIA))
    }
}
