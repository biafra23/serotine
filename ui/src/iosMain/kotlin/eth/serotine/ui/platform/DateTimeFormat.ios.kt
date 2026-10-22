package eth.serotine.ui.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

/** Foundation's medium date and short time, in the device's time zone and locale. */
actual fun formatDateTime(epochSeconds: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))
}

/** Foundation's hours, minutes, seconds and milliseconds, in the device's time zone as it is now. */
actual fun formatTimeOfDay(epochMillis: Long): String =
    timeOfDay.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))

/**
 * Made once, as a debug log formats a time per line (a formatter is costly to make, and safe to
 * share between threads since iOS 7). A fixed format in en_US_POSIX, so the user's 12-hour setting
 * cannot turn `HH` into an hour with AM/PM (Apple's QA1480); the time zone follows the device's.
 */
private val timeOfDay: NSDateFormatter by lazy {
    NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = NSTimeZone.localTimeZone
        dateFormat = "HH:mm:ss.SSS"
    }
}
