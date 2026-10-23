package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.LastExchangeRate
import kotlinx.coroutines.flow.first

/**
 * Reads the exchange rate a home-screen widget shows and keeps it ([LastExchangeRate]): one
 * unit of the [network]'s native currency in the user's display currency as the screens have it
 * ([ObserveDisplayCurrencies]: USD where none is chosen or the network does not offer the one
 * stored), or in USD where that is the native currency itself (docs/wallet-ux.md, *Home-screen
 * widget*). Only a verified rate is kept; a read that gets none leaves the last one as it is.
 */
class RefreshExchangeRate(
    private val currencies: ObserveDisplayCurrencies,
    private val readRate: ReadExchangeRate,
    private val last: LastExchangeRate,
    private val network: Network,
) {
    /** Reads the rate and keeps it; the rate kept, or null when none was read. */
    suspend operator fun invoke(): RateSnapshot? {
        val read = readRate(network, network.nativePriceId, quote()) as? RateResult.Read ?: return null
        last.save(network, read.snapshot)
        return read.snapshot
    }

    /**
     * Whether a read is due at [nowSeconds] (the device's clock, which only schedules a read and
     * never dates a price): none is kept, the kept one is in another currency than the one
     * shown now, or it was read more than [maxAgeSeconds] before.
     */
    suspend fun isDue(nowSeconds: Long, maxAgeSeconds: Long): Boolean {
        val kept = last.load(network) ?: return true
        return kept.quote != quote() || nowSeconds - kept.readAt > maxAgeSeconds
    }

    /** The currency the rate is shown in. */
    private suspend fun quote(): DisplayCurrency {
        val chosen = currencies.on(network).first()
        return chosen.takeIf { it.priceId != network.nativePriceId } ?: DisplayCurrency.USD
    }
}
