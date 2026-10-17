package eth.serotine.domain.usecase

import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.repository.PriceFeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The display currency (docs/wallet-ux.md, screen 19): the one chosen, and the ones offered,
 * those whose own USD rate has a feed on the network. A choice the network does not offer, or
 * none yet, is USD.
 */
class ObserveDisplayCurrencies(
    private val feeds: PriceFeeds,
    private val setting: DisplayCurrencySetting,
) {
    operator fun invoke(): Flow<DisplayCurrencies> = setting.observe().map { choice ->
        val offered = offered(feeds)
        val chosen = (choice as? CurrencyChoice.Chosen)?.currency?.takeIf { it in offered }
        DisplayCurrencies(
            chosen ?: DisplayCurrency.USD,
            offered,
            unreadable = choice is CurrencyChoice.Unreadable,
            setAside = (choice as? CurrencyChoice.Chosen)?.setAside == true,
        )
    }
}

/** Chooses the display currency; false when the network does not offer it or it could not be saved. */
class SetDisplayCurrency(
    private val feeds: PriceFeeds,
    private val setting: DisplayCurrencySetting,
) {
    suspend operator fun invoke(currency: DisplayCurrency): Boolean =
        currency in offered(feeds) && setting.choose(currency)
}

/**
 * The [chosen] display currency, the [offered] ones in their order, whether the stored choice
 * cannot be read ([unreadable]: [chosen] is then USD, and nothing can be chosen), and whether
 * it was damaged and set aside, so [chosen] is USD until another is chosen ([setAside]).
 */
data class DisplayCurrencies(
    val chosen: DisplayCurrency,
    val offered: List<DisplayCurrency>,
    val unreadable: Boolean = false,
    val setAside: Boolean = false,
)

private fun offered(feeds: PriceFeeds): List<DisplayCurrency> =
    DisplayCurrency.entries.filter { it.priceId == PriceId.USD || it.priceId in feeds.quotes }
