package eth.serotine.ui.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.DisplayCurrency
import eth.serotine.domain.usecase.ObserveDisplayCurrencies
import eth.serotine.domain.usecase.SetDisplayCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The display currency screen's ViewModel: the currencies offered, and choosing one ([choose]). */
class CurrencyViewModel(
    observeDisplayCurrencies: ObserveDisplayCurrencies,
    private val setDisplayCurrency: SetDisplayCurrency,
) : ViewModel() {

    private class Outcome(val error: String? = null, val done: Boolean = false)

    private val outcome = MutableStateFlow(Outcome())

    val uiState: StateFlow<CurrencyUiState> = combine(observeDisplayCurrencies(), outcome) { currencies, outcome ->
        CurrencyUiState(
            options = currencies.offered.map { CurrencyOption(it.code, nameOf(it), chosen = it == currencies.chosen) },
            notice = if (currencies.unreadable) UNREADABLE else outcome.error,
            canChoose = !currencies.unreadable,
            done = outcome.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrencyUiState())

    /** Chooses the currency with [code]; the screen leaves once it is saved. */
    fun choose(code: String) {
        val currency = DisplayCurrency.entries.firstOrNull { it.code == code } ?: return
        if (outcome.value.done) return
        viewModelScope.launch {
            outcome.value = if (setDisplayCurrency(currency)) Outcome(done = true) else Outcome(error = NOT_SAVED)
        }
    }

    private fun nameOf(currency: DisplayCurrency): String = when (currency) {
        DisplayCurrency.USD -> "US dollar"
        DisplayCurrency.EUR -> "Euro"
        DisplayCurrency.GBP -> "Pound sterling"
        DisplayCurrency.CHF -> "Swiss franc"
        DisplayCurrency.JPY -> "Japanese yen"
        DisplayCurrency.ETH -> "Ether"
        DisplayCurrency.BTC -> "Bitcoin"
    }

    private companion object {
        const val NOT_SAVED = "The choice could not be saved. Nothing has changed."
        const val UNREADABLE = "Your choice of currency cannot be read right now, so values are in USD and " +
            "nothing can be chosen. Serotine keeps trying."
    }
}
