package eth.serotine.ui.components

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

/** [clock]'s now (unix seconds), every second: what a screen's ages are re-dated by. */
internal fun ticking(clock: () -> Long): Flow<Long> = flow {
    while (true) {
        emit(clock())
        delay(1.seconds)
    }
}

/** An age of [seconds] as a screen says it: "8 s", "2 min", "3 h". */
internal fun ageOf(seconds: Long): String = when {
    seconds < 120 -> "$seconds s"
    seconds < 7_200 -> "${seconds / 60} min"
    else -> "${seconds / 3_600} h"
}

/** "just now" or "2 min ago": how long before [now] a time [at] was, both in unix seconds. */
internal fun agoText(at: Long, now: Long): String {
    val seconds = now - at
    return if (seconds < 1) "just now" else "${ageOf(seconds)} ago"
}
