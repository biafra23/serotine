package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenBalanceReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.math.pow

/**
 * What an account holds and what it is worth in the display currency (docs/wallet-ux.md,
 * screens 1 and 4, and *Prices*): its verified native balance and token holdings, each valued
 * at its feed's price, and the [AccountValue.total] of the priced ones. The prices are read
 * one per price id ([readEach]), so a token added or the currency changed starts only the
 * reading it needs. A price is display-only: nothing here is ever part of an amount signed.
 */
class ObserveAccountValue(
    private val observeNativeBalance: ObserveNativeBalance,
    private val observeTokenHoldings: ObserveTokenHoldings,
    private val observeUsdPrice: ObserveUsdPrice,
    private val observeDisplayCurrencies: ObserveDisplayCurrencies,
    private val network: Network,
) {
    operator fun invoke(holder: Address): Flow<AccountValue> {
        val native = observeNativeBalance(holder).map<BalanceReading, BalanceReading?> { it }.onStart { emit(null) }
        // Like the native balance, the holdings never hold the account back: none until they come.
        val holdings = observeTokenHoldings(holder).onStart { emit(TokenHoldings(emptyList())) }
        val currency = observeDisplayCurrencies().map { it.chosen }.distinctUntilChanged()
        return combine(native, holdings, currency) { balance, held, chosen -> Inputs(balance, held, chosen) }
            .readEach(keys = ::priceIds, read = { observeUsdPrice(it) })
            .map { (inputs, usd) -> valued(inputs, usd) }
    }

    private class Inputs(val native: BalanceReading?, val holdings: TokenHoldings, val currency: DisplayCurrency)

    private fun priceIds(inputs: Inputs): Set<PriceId> = buildSet {
        add(inputs.currency.priceId)
        add(network.nativePriceId)
        inputs.holdings.holdings.mapNotNullTo(this) { it.token.priceId }
    }

    private fun valued(inputs: Inputs, usd: Map<PriceId, Price>): AccountValue {
        val rate = usd[inputs.currency.priceId] ?: Price.Checking
        fun price(id: PriceId?): Price = id?.let { (usd[it] ?: Price.Checking) inUnitsOf rate }
            ?: Price.NoPrice(NoPriceReason.NO_FEED)
        val native = inputs.native
        return AccountValue(
            currency = inputs.currency,
            native = native,
            nativeValue = (native as? BalanceReading.Verified)
                ?.let { valuation(it.wei.decimal, NATIVE_DECIMALS, price(network.nativePriceId)) },
            tokens = inputs.holdings.holdings.map { holding ->
                val units = (holding.reading as? TokenBalanceReading.Verified)?.units
                val value = units?.let { valuation(it.decimal, holding.token.decimals, price(holding.token.priceId)) }
                TokenValue(holding, value)
            },
            settingsUnavailable = inputs.holdings.settingsUnavailable,
        )
    }

    private companion object {
        /** Decimal places of ether and of every EVM chain's native currency. */
        const val NATIVE_DECIMALS = 18
    }
}

/**
 * An account's assets valued in [currency]: its [native] balance (null before the first
 * reading) with its value ([nativeValue], null while the balance is not verified), and each
 * shown token's holding with its value ([tokens]); [settingsUnavailable] when the user's
 * token settings cannot be read, so the tokens ignore them.
 */
data class AccountValue(
    val currency: DisplayCurrency,
    val native: BalanceReading?,
    val nativeValue: Valuation?,
    val tokens: List<TokenValue>,
    val settingsUnavailable: Boolean = false,
) {
    /**
     * The value of the priced holdings (docs/wallet-ux.md, *Prices*: totals add only priced
     * holdings, and say how many assets they left out): a held asset without a price, or with
     * a stale one, is left out and counted; one whose balance or price is still being read, or
     * cannot be verified right now, makes the total incomplete.
     */
    val total: Total
        get() {
            var total = Total.of(native?.let(::heldNative) ?: Holding.Unknown, nativeValue)
            tokens.forEach { token -> total += Total.of(heldToken(token.holding.reading), token.value) }
            return total
        }

    private fun heldNative(reading: BalanceReading): Holding = when (reading) {
        is BalanceReading.Verified -> if (reading.wei.decimal == "0") Holding.None else Holding.Some
        BalanceReading.Syncing, is BalanceReading.Unavailable -> Holding.Unknown
    }

    private fun heldToken(reading: TokenBalanceReading?): Holding = when (reading) {
        is TokenBalanceReading.Verified -> if (reading.units.isZero) Holding.None else Holding.Some
        TokenBalanceReading.NotAToken -> Holding.None
        null, TokenBalanceReading.Syncing, is TokenBalanceReading.Unavailable -> Holding.Unknown
    }
}

/** A shown token's [holding] and its [value] in the display currency, null while its balance is not verified. */
data class TokenValue(val holding: TokenHolding, val value: Valuation?)

/** What an amount is worth in the display currency. */
sealed interface Valuation {

    /** Its price is not read yet. */
    data object Checking : Valuation

    /**
     * [amount] at a price [ageSeconds] old at the verified head; [stale] when it is older than
     * its feed's heartbeat.
     */
    data class Valued(val amount: Double, val ageSeconds: Long, val stale: Boolean) : Valuation

    /** It has no price, for [reason]. */
    data class NoPrice(val reason: NoPriceReason) : Valuation

    /** No verified price can be had right now, for the reason [reason] gives. */
    data class Unavailable(val reason: ReadUnavailable) : Valuation
}

/**
 * A total in the display currency: [amount], the held assets [leftOut] for having no fresh
 * price, and whether every balance and price it needed was read ([complete]).
 */
data class Total(val amount: Double, val leftOut: Int, val complete: Boolean) {

    operator fun plus(other: Total): Total =
        Total(amount + other.amount, leftOut + other.leftOut, complete && other.complete)

    companion object {
        /** Nothing, nothing left out, nothing missing. */
        val NONE = Total(0.0, leftOut = 0, complete = true)

        internal fun of(held: Holding, value: Valuation?): Total = when (held) {
            Holding.None -> NONE
            Holding.Unknown -> Total(0.0, leftOut = 0, complete = false)
            Holding.Some -> when (value) {
                null, Valuation.Checking -> Total(0.0, leftOut = 0, complete = false)
                is Valuation.Valued -> if (value.stale) {
                    Total(0.0, leftOut = 1, complete = true)
                } else {
                    Total(value.amount, leftOut = 0, complete = true)
                }
                is Valuation.NoPrice, is Valuation.Unavailable -> Total(0.0, leftOut = 1, complete = true)
            }
        }
    }
}

/** Whether an asset is held, as its verified balance says. */
internal enum class Holding { None, Some, Unknown }

/** [units] of an asset with [decimals] at [price]: display-only, so a double is precise enough. */
internal fun valuation(units: String, decimals: Int, price: Price): Valuation = when (price) {
    Price.Checking -> Valuation.Checking
    is Price.Priced ->
        Valuation.Valued(units.toDouble() / 10.0.pow(decimals) * price.perUnit, price.ageSeconds, price.stale)
    is Price.NoPrice -> Valuation.NoPrice(price.reason)
    is Price.Unavailable -> Valuation.Unavailable(price.reason)
}
