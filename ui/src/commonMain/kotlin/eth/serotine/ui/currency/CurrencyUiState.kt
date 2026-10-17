package eth.serotine.ui.currency

/**
 * Everything the display currency screen renders (docs/wallet-ux.md, screen 19): the
 * currencies the network offers, the chosen one marked, and [notice], why nothing can be
 * chosen right now or what the last choice could not do; [done] once a choice is saved, and
 * the screen leaves.
 */
data class CurrencyUiState(
    val options: List<CurrencyOption> = emptyList(),
    val notice: String? = null,
    val canChoose: Boolean = true,
    val done: Boolean = false,
)

/** One currency: its [code] ("EUR"), its [name] ("Euro"), and whether it is the [chosen] one. */
data class CurrencyOption(val code: String, val name: String, val chosen: Boolean)
