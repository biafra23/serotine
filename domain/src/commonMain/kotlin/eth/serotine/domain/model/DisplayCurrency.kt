package eth.serotine.domain.model

/**
 * A currency fiat values can be shown in (docs/wallet-ux.md, screen 19): its [code], the
 * [priceId] of its own USD rate, and the [fractionDigits] an amount in it is shown to. A
 * network offers only those whose rate has a pinned feed there; [USD] needs none.
 */
enum class DisplayCurrency(val code: String, val priceId: PriceId, val fractionDigits: Int) {
    USD("USD", PriceId.USD, 2),
    EUR("EUR", PriceId("EUR"), 2),
    GBP("GBP", PriceId("GBP"), 2),
    CHF("CHF", PriceId("CHF"), 2),
    JPY("JPY", PriceId("JPY"), 0),
    ETH("ETH", PriceId("ETH"), 4),
    BTC("BTC", PriceId("BTC"), 6),
}
