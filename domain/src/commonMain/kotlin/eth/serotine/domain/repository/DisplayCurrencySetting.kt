package eth.serotine.domain.repository

import eth.serotine.domain.model.DisplayCurrency
import kotlinx.coroutines.flow.Flow

/**
 * The port for the user's choice of display currency (docs/wallet-ux.md, screen 19). :data
 * keeps it in a file, so it survives a restart. It is a display setting: no amount is ever
 * entered, reviewed or signed in it.
 */
interface DisplayCurrencySetting {
    /** The choice, re-emitted on each change, or that it cannot be read. Never throws. */
    fun observe(): Flow<CurrencyChoice>

    /** Chooses [currency]; false when the choice could not be saved, and nothing changed. Never throws. */
    suspend fun choose(currency: DisplayCurrency): Boolean
}

/** What [DisplayCurrencySetting.observe] emits. */
sealed interface CurrencyChoice {
    /**
     * The [currency] chosen, or null while none has been; [setAside] when no choice is left
     * because the stored one was damaged and set aside.
     */
    data class Chosen(val currency: DisplayCurrency?, val setAside: Boolean = false) : CurrencyChoice

    /** A choice is stored but cannot be read; nothing can be chosen until it can. */
    data object Unreadable : CurrencyChoice
}
