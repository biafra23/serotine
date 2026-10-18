package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.model.PriceId
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.CurrencyChoice
import eth.serotine.domain.repository.DisplayCurrencySetting
import eth.serotine.domain.repository.FeedReading
import eth.serotine.domain.repository.NativeBalances
import eth.serotine.domain.repository.NoPriceReason
import eth.serotine.domain.repository.PriceFeeds
import eth.serotine.domain.repository.TokenBalanceReading
import eth.serotine.domain.repository.TokenBalances
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** [PriceFeeds] with the feeds of [quotes], each answering what a test sets; Syncing until then. */
internal class FakeFeeds(override val quotes: Map<PriceId, PriceId>) : PriceFeeds {
    private val readings = mutableMapOf<PriceId, MutableStateFlow<FeedReading>>()

    fun of(id: PriceId) = readings.getOrPut(id) { MutableStateFlow(FeedReading.Syncing) }

    /** Sets the feed of [id] to a fresh [price], updated at [updatedAt], [ageSeconds] old. */
    fun answer(id: PriceId, price: Double, updatedAt: Long = 1_000, ageSeconds: Long = 60, stale: Boolean = false) {
        of(id).value = FeedReading.Verified(price, updatedAt, ageSeconds, stale)
    }

    override fun observe(priceId: PriceId): Flow<FeedReading> =
        if (priceId in quotes) of(priceId) else flowOf(FeedReading.NoPrice(NoPriceReason.NO_FEED))
}

/** A [DisplayCurrencySetting] in memory; [failSaves] makes a choice fail. */
internal class MemoryCurrencySetting : DisplayCurrencySetting {
    val choice = MutableStateFlow<CurrencyChoice>(CurrencyChoice.Chosen(null))
    var failSaves = false

    override fun observe(): Flow<CurrencyChoice> = choice

    override suspend fun choose(currency: DisplayCurrency): Boolean {
        if (failSaves || choice.value is CurrencyChoice.Unreadable) return false
        choice.value = CurrencyChoice.Chosen(currency)
        return true
    }
}

/** [NativeBalances] answering what a test sets, per address; Syncing until then. */
internal class FakeNativeBalances : NativeBalances {
    private val readings = mutableMapOf<Address, MutableStateFlow<BalanceReading>>()

    fun of(address: Address) = readings.getOrPut(address) { MutableStateFlow(BalanceReading.Syncing) }

    override fun observeBalance(address: Address): Flow<BalanceReading> = of(address)
}

/** [TokenBalances] answering what a test sets, per holder and token; Syncing until then. */
internal class FakeTokenBalances : TokenBalances {
    private val readings = mutableMapOf<Pair<Address, Address>, MutableStateFlow<TokenBalanceReading>>()

    fun of(holder: Address, token: Address) =
        readings.getOrPut(holder to token) { MutableStateFlow(TokenBalanceReading.Syncing) }

    override fun observeBalance(holder: Address, token: Address): Flow<TokenBalanceReading> = of(holder, token)
}

internal val ETH = PriceId("ETH")
internal val LINK_ID = PriceId("LINK")
internal val EUR = PriceId("EUR")
