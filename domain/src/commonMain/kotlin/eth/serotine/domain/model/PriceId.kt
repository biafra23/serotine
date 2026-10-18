package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * What a price is of, as a network's feed table names it: an asset ("ETH", "LINK") or a
 * currency ("EUR"). Every price goes through [USD] (docs/wallet-ux.md, *Prices*).
 */
@JvmInline
value class PriceId(val id: String) {
    init {
        require(id.isNotEmpty() && id.all { it in 'A'..'Z' || it in '0'..'9' }) { "not a price id: '$id'" }
    }

    override fun toString(): String = id

    companion object {
        /** The US dollar: what every feed's price is converted to first. */
        val USD = PriceId("USD")
    }
}
