package eth.serotine.domain.usecase

import eth.serotine.domain.model.RateSnapshot
import eth.serotine.domain.repository.DisplayCurrencySetting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Keeps the exchange rate a home-screen widget shows current while the light client runs anyway
 * (the app in front): at the start, on each change of the display currency, and every
 * [checkEvery] after, it reads the rate when one is due ([RefreshExchangeRate.isDue]: older than
 * [maxAgeSeconds] by [nowSeconds], the device's clock), each read given [readTimeout] to wait for
 * the light client, and hands each rate kept to [onRead]. Reading only when due keeps the calls
 * it adds to the ones the screens make anyway few: the rate now is the screens' own reading
 * when they show prices, and the rate a day before a handful of calls a check. Runs until
 * cancelled.
 */
class FollowExchangeRate(
    private val currency: DisplayCurrencySetting,
    private val refresh: RefreshExchangeRate,
    private val maxAgeSeconds: Long,
    private val checkEvery: Duration,
    private val readTimeout: Duration,
    private val nowSeconds: () -> Long = ::epochSecondsNow,
) {
    suspend operator fun invoke(onRead: suspend (RateSnapshot) -> Unit) {
        currency.observe().distinctUntilChanged().collectLatest {
            while (true) {
                if (refresh.isDue(nowSeconds(), maxAgeSeconds)) {
                    withTimeoutOrNull(readTimeout) { refresh() }?.let { onRead(it) }
                }
                delay(checkEvery)
            }
        }
    }
}
