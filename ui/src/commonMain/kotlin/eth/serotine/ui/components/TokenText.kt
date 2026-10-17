package eth.serotine.ui.components

/** The most characters of a token's symbol a screen shows: real ones are far shorter. */
internal const val SYMBOL_CHARS = 24

/** The most characters of a token's name a screen shows. */
internal const val NAME_CHARS = 48

/**
 * A token's [symbol] as screens show it: what a contract says may be a thousand characters,
 * which would stretch every row it is in, so a longer one is cut, an ellipsis marking the cut.
 */
internal fun displaySymbol(symbol: String): String = clipped(symbol, SYMBOL_CHARS)

/** A token's [name] as screens show it, cut like [displaySymbol] beyond [NAME_CHARS]. */
internal fun displayName(name: String): String = clipped(name, NAME_CHARS)

private fun clipped(text: String, max: Int): String {
    if (text.length <= max) return text
    // Never between the two halves of a character beyond the BMP.
    val cut = if (text[max - 1].isHighSurrogate()) max - 1 else max
    return text.take(cut).trimEnd() + "…"
}
