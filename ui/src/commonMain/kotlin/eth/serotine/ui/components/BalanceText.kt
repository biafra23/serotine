package eth.serotine.ui.components

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.BalanceReading
import eth.serotine.domain.repository.ReadUnavailable

/** Decimal places of ether and of every EVM chain's native currency. */
internal const val NATIVE_DECIMALS = 18

/**
 * [wei] in whole units of [symbol], truncated to [places] decimal places, never rounded up:
 * a row never shows more than the account holds. A balance too small for [places] shows as
 * "< 0.0001", never as zero.
 */
internal fun formatNative(wei: Wei, symbol: String, places: Int = 4): String =
    formatUnits(wei.decimal, NATIVE_DECIMALS, symbol, places)

/**
 * [units] (a non-negative decimal integer in a token's smallest unit) in whole units of
 * [symbol] at [decimals] decimal places, truncated to [places] of them, never rounded up; a
 * non-zero amount too small for [places] shows as "< 0.0001", never as zero.
 */
internal fun formatUnits(units: String, decimals: Int, symbol: String, places: Int = 4): String {
    val digits = units.padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val fraction = digits.takeLast(decimals).take(places).trimEnd('0')
    return when {
        fraction.isNotEmpty() -> "$whole.$fraction $symbol"
        whole == "0" && units != "0" -> "< 0.${"0".repeat(places - 1)}1 $symbol"
        else -> "$whole $symbol"
    }
}

/** What an account row says for [reading] on [network]: an amount, or why there is none. */
internal fun balanceText(reading: BalanceReading, network: Network): String = when (reading) {
    is BalanceReading.Verified -> formatNative(reading.wei, network.nativeSymbol)
    BalanceReading.Syncing -> "Syncing…"
    is BalanceReading.Unavailable -> when (reading.reason) {
        ReadUnavailable.NO_ENGINE -> "No light client"
        ReadUnavailable.STALE_ANCHOR -> "Sync refused"
        ReadUnavailable.UNPROVEN -> "Not verified"
        ReadUnavailable.REFUSED -> "Not readable here"
    }
}

/** 11922744 → "11,922,744": a block number as the screens show it. */
internal fun grouped(number: Long): String = number.toString().reversed().chunked(3).joinToString(",").reversed()
