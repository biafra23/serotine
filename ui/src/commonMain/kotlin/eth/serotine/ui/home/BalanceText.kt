package eth.serotine.ui.home

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading

/** Decimal places of ether and of every EVM chain's native currency. */
private const val NATIVE_DECIMALS = 18

/**
 * [wei] in whole units of [symbol], truncated to [places] decimal places, never rounded up:
 * a row never shows more than the account holds. A balance too small for [places] shows as
 * "< 0.0001", never as zero.
 */
internal fun formatNative(wei: Wei, symbol: String, places: Int = 4): String {
    val digits = wei.decimal.padStart(NATIVE_DECIMALS + 1, '0')
    val whole = digits.dropLast(NATIVE_DECIMALS)
    val fraction = digits.takeLast(NATIVE_DECIMALS).take(places).trimEnd('0')
    return when {
        fraction.isNotEmpty() -> "$whole.$fraction $symbol"
        whole == "0" && wei.decimal != "0" -> "< 0.${"0".repeat(places - 1)}1 $symbol"
        else -> "$whole $symbol"
    }
}

/** What an account row says for [reading] on [network]: an amount, or why there is none. */
internal fun balanceText(reading: BalanceReading, network: Network): String = when (reading) {
    is BalanceReading.Verified -> formatNative(reading.wei, network.nativeSymbol)
    BalanceReading.Syncing -> "Syncing…"
    is BalanceReading.Unavailable -> when (reading.reason) {
        BalanceReading.Reason.NO_ENGINE -> "No light client"
        BalanceReading.Reason.STALE_ANCHOR -> "Sync refused"
        BalanceReading.Reason.UNPROVEN -> "Not verified"
    }
}
