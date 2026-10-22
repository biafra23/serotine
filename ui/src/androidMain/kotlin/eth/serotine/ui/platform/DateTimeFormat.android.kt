package eth.serotine.ui.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** java.time's localized medium date and short time, in the device's time zone (API 26+, below our minSdk). */
actual fun formatDateTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** java.time's hours, minutes, seconds and milliseconds, in the system's time zone. */
actual fun formatTimeOfDay(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
