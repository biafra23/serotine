package eth.serotine.ui.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

/** Foundation's medium date and short time, in the device's time zone and locale. */
actual fun formatDateTime(epochSeconds: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))
}

/** Foundation's hours, minutes, seconds and milliseconds, in the device's time zone. */
actual fun formatTimeOfDay(epochMillis: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm:ss.SSS"
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))
}
