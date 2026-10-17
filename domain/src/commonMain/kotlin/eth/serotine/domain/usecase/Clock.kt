package eth.serotine.domain.usecase

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Now, in unix seconds: the default clock of the use cases that compare against an activation time. */
@OptIn(ExperimentalTime::class)
internal fun epochSecondsNow(): Long = Clock.System.now().epochSeconds
