package eth.serotine.ui.platform

/**
 * [epochSeconds] (unix seconds) as a date and time in the user's own time zone and locale,
 * for a sentence: "Nov 6, 2026, 3:00 PM" in English. What the screens and notifications date
 * a network upgrade with.
 */
expect fun formatDateTime(epochSeconds: Long): String
