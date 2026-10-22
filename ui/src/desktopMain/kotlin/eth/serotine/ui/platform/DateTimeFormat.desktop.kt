package eth.serotine.ui.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** java.time's localized medium date and short time, in the system's time zone. */
actual fun formatDateTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** java.time's hours, minutes, seconds and milliseconds, in the system's time zone as it is now. */
actual fun formatTimeOfDay(epochMillis: Long): String =
    TIME_OF_DAY.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

/** Parsed once: a debug log formats a time per line. Immutable, so any thread may use it. */
private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
